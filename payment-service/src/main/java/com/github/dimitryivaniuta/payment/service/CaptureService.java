package com.github.dimitryivaniuta.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;

import com.github.dimitryivaniuta.payment.api.dto.CaptureRequest;
import com.github.dimitryivaniuta.payment.api.dto.CaptureView;
import com.github.dimitryivaniuta.payment.api.mapper.PaymentMappers;
import com.github.dimitryivaniuta.payment.domain.model.CaptureEntity;
import com.github.dimitryivaniuta.payment.domain.model.CaptureStatus;
import com.github.dimitryivaniuta.payment.domain.model.PaymentEntity;
import com.github.dimitryivaniuta.payment.domain.model.PaymentStatus;
import com.github.dimitryivaniuta.payment.domain.repo.CaptureRepository;
import com.github.dimitryivaniuta.payment.domain.repo.PaymentRepository;
import com.github.dimitryivaniuta.payment.outbox.OutboxStore;
import com.github.dimitryivaniuta.payment.saga.events.PaymentEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import static java.util.Objects.requireNonNull;

/**
 * Handles partial/multiple captures for a Payment.
 * Workflow:
 *  - load payment & validate state
 *  - decide capture amount/currency (defaults to remaining amount, payment currency)
 *  - call provider, persist Capture (PENDING -> SUCCEEDED/FAILED)
 *  - update Payment status: CAPTURING (partial) or CAPTURED (fully captured)
 *  - post ledger entries (on success) and emit PAYMENT_CAPTURED event via outbox
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptureService {

    // Dependencies
    private final PaymentRepository paymentRepo;
    private final CaptureRepository captureRepo;
    private final LedgerService ledger;
    private final OutboxStore outbox;
    private final ObjectMapper om;
    private final FakePaymentProvider provider;
    private final TransactionalOperator tx;
    private final DatabaseClient db;

    /**
     * Capture a payment (partial or full).
     * If {@code amountMinor} is null → captures the remaining authorized amount.
     * If {@code currencyCode} is null → uses the payment currency.
     */
    public Mono<CaptureView> capture(long paymentId, CaptureRequest req) {
        requireNonNull(req, "req");

        return paymentRepo.findById(paymentId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Payment not found: " + paymentId)))
                .flatMap(pmt -> validatePaymentForCapture(pmt)
                        .then(remainingAmount(pmt.getId()))
                        .flatMap(alreadyCaptured -> {
                            final long remaining = Math.max(0, pmt.getAmountMinor() - alreadyCaptured);
                            final Long requested = req.amountMinor();
                            final long toCapture = requested == null ? remaining : requested;
                            if (toCapture <= 0) {
                                return Mono.error(new IllegalStateException("Nothing to capture (remaining=" + remaining + ")"));
                            }
                            final String currencyReq = req.currencyCode();
                            final String currency = (currencyReq == null || currencyReq.isBlank())
                                    ? pmt.getCurrencyCode()
                                    : normalizeCurrency(currencyReq);

                            if (!currency.equalsIgnoreCase(pmt.getCurrencyCode())) {
                                return Mono.error(new IllegalArgumentException("Capture currency must match payment currency"));
                            }

                            // Persist everything atomically
                            return tx.transactional(
                                    createPendingCapture(pmt, toCapture, currency)
                                            .flatMap(capture -> invokeProviderAndFinalize(pmt, capture, alreadyCaptured))
                            );
                        })
                )
                .map(PaymentMappers::toView);
    }

    /* ============================ internals ============================ */

    private Mono<Void> validatePaymentForCapture(PaymentEntity pmt) {
        final PaymentStatus st = pmt.getStatus();
        if (st != PaymentStatus.AUTHORIZED && st != PaymentStatus.CAPTURING) {
            return Mono.error(new IllegalStateException("Capture requires AUTHORIZED or CAPTURING status; was " + st));
        }
        return Mono.empty();
    }

    /** Sum of SUCCEEDED captures so far (minor units). */
    private Mono<Long> remainingAmount(Long paymentId) {
        return db.sql("""
                SELECT COALESCE(SUM(amount_minor),0) AS total
                FROM captures
                WHERE payment_id = :pid AND status = 'SUCCEEDED'
                """)
                .bind("pid", paymentId)
                .map((row, meta) -> {
                    Long v = row.get("total", Long.class);
                    return v == null ? 0L : v;
                })
                .one()
                .defaultIfEmpty(0L);
    }

    private Mono<CaptureEntity> createPendingCapture(PaymentEntity pmt, long amountMinor, String currency) {
        final Instant now = Instant.now();
        CaptureEntity pending = CaptureEntity.builder()
                .tenantId(pmt.getTenantId())
                .paymentId(pmt.getId())
                .amountMinor(amountMinor)
                .currencyCode(currency)
                .status(CaptureStatus.PENDING)
                .psp(pmt.getPsp())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return captureRepo.save(pending);
    }

    private Mono<CaptureEntity> invokeProviderAndFinalize(PaymentEntity pmt,
                                                          CaptureEntity capture,
                                                          long alreadyCaptured) {
        // Call provider (uses auth ref from payment)
        var result = provider.capture(capture.getAmountMinor(), capture.getCurrencyCode(), pmt.getPspRef());
        if (result.authorized()) {
            capture.setStatus(CaptureStatus.SUCCEEDED);
            capture.setPspCaptureRef(result.externalRef());
            capture.setUpdatedAt(Instant.now());

            final long newTotal = alreadyCaptured + capture.getAmountMinor();
            final boolean fullyCaptured = newTotal >= pmt.getAmountMinor();
            pmt.setStatus(fullyCaptured ? PaymentStatus.CAPTURED : PaymentStatus.CAPTURING);
            pmt.setUpdatedAt(Instant.now());

            return captureRepo.save(capture)
                    .flatMap(savedCap ->
                            paymentRepo.save(pmt)
                                    .then(ledger.postCapture(pmt, savedCap))
                                    .then(emitCaptured(pmt, savedCap))
                                    .thenReturn(savedCap)
                    );

        } else {
            capture.setStatus(CaptureStatus.FAILED);
            capture.setUpdatedAt(Instant.now());
            return captureRepo.save(capture);
        }
    }

    private Mono<Void> emitCaptured(PaymentEntity p, CaptureEntity c) {
        var evt = PaymentEvents.Captured.builder()
                .sagaId(p.getSagaId())
                .tenantId(p.getTenantId())
                .orderId(p.getOrderId())
                .paymentId(p.getId())
                .captureId(c.getId())
                .amountMinor(c.getAmountMinor())
                .currencyCode(c.getCurrencyCode())
                .psp(c.getPsp())
                .pspCaptureRef(c.getPspCaptureRef())
                .ts(Instant.now())
                .build();
        try {
            String json = om.writeValueAsString(evt);
            return outbox.saveEvent(
                            p.getTenantId(),
                            p.getSagaId(),
                            "payment",
                            p.getId(),
                            evt.type(),
                            String.valueOf(p.getId()),
                            json,
                            Map.of())
                    .then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    private static String normalizeCurrency(String code) {
        String cur = requireNonBlank(code, "currencyCode").toUpperCase();
        try { Currency.getInstance(cur); } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ISO currency code: " + cur, e);
        }
        return cur;
    }

    private static String requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return v;
    }
}
