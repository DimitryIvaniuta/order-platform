package com.github.dimitryivaniuta.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.payment.api.dto.RefundRequest;
import com.github.dimitryivaniuta.payment.api.dto.RefundView;
import com.github.dimitryivaniuta.payment.api.mapper.PaymentMappers;
import com.github.dimitryivaniuta.payment.domain.model.CaptureEntity;
import com.github.dimitryivaniuta.payment.domain.model.PaymentEntity;
import com.github.dimitryivaniuta.payment.domain.model.PaymentStatus;
import com.github.dimitryivaniuta.payment.domain.model.RefundEntity;
import com.github.dimitryivaniuta.payment.domain.model.RefundStatus;
import com.github.dimitryivaniuta.payment.domain.repo.CaptureRepository;
import com.github.dimitryivaniuta.payment.domain.repo.PaymentRepository;
import com.github.dimitryivaniuta.payment.domain.repo.RefundRepository;
import com.github.dimitryivaniuta.payment.outbox.OutboxStore;
import com.github.dimitryivaniuta.payment.saga.events.PaymentEvents;
import com.github.dimitryivaniuta.payment.service.FakePaymentProvider;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import static java.util.Objects.requireNonNull;

/**
 * Handles partial/multiple refunds for a Payment:
 *  • Validates state (must not be FAILED/CANCELLED)
 *  • Ensures total refunds (PENDING+SUCCEEDED) do not exceed total captured (SUCCEEDED)
 *  • Calls the payment provider and records RefundEntity
 *  • Posts double-entry ledger entries (on success)
 *  • Emits PAYMENT_REFUNDED via outbox
 *
 * All amounts are minor units. Currency must match the Payment currency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    // Repositories
    private final PaymentRepository paymentRepo;
    private final RefundRepository refundRepo;
    private final CaptureRepository captureRepo; // used for ref lookups (latest succeeded capture)

    // Infra & collaborators
    private final LedgerService ledger;
    private final OutboxStore outbox;
    private final ObjectMapper om;
    private final TransactionalOperator tx;
    private final DatabaseClient db;
    private final FakePaymentProvider provider;

    /**
     * Refund a payment (partial or full).
     * - amountMinor is required by your DTO
     * - currencyCode must equal payment currency
     */
    public Mono<RefundView> refund(long paymentId, RefundRequest req) {
        requireNonNull(req, "req");
        requireNonNull(req.amount(), "amount");
        requireNonNull(req.amount().value(), "amount.value");
        requireNonNull(req.amount().currency(), "amount.currency");

        final long amount       = req.amount().value();
        final String currencyReq= req.amount().currency();
        final String reasonCode = req.reference(); // reuse client reference as reason/idempotency tag

        return paymentRepo.findById(paymentId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Payment not found: " + paymentId)))
                .flatMap(pmt -> validatePaymentForRefund(pmt)
                        .then(checkCurrencyMatches(pmt, currencyReq))
                        .then(ensureRefundWithinCaptured(pmt.getId(), amount))
                        .flatMap(refundableLeft -> {
                            if (amount > refundableLeft) {
                                return Mono.error(new IllegalStateException(
                                        "Refund amount exceeds refundable balance (left=" + refundableLeft + ")"));
                            }
                            return latestSucceededCaptureRef(pmt.getId())
                                    .switchIfEmpty(Mono.error(new IllegalStateException(
                                            "Cannot refund: no successful capture found")))
                                    .flatMap(captureRef -> tx.transactional(
                                            createPendingRefund(pmt, amount, pmt.getCurrencyCode(), reasonCode)
                                                    .flatMap(ref -> invokeProviderAndFinalize(pmt, ref, captureRef))
                                    ));
                        }))
                .map(PaymentMappers::toView);
    }

    private Mono<Void> validatePaymentForRefund(PaymentEntity pmt) {
        final PaymentStatus st = pmt.getStatus();
        if (st == PaymentStatus.FAILED || st == PaymentStatus.CANCELLED) {
            return Mono.error(new IllegalStateException("Cannot refund payment in status " + st));
        }
        return Mono.empty();
    }

    private Mono<Void> checkCurrencyMatches(PaymentEntity pmt, String requestedCurrency) {
        final String normalized = normalizeCurrency(requestedCurrency);
        if (!normalized.equalsIgnoreCase(pmt.getCurrencyCode())) {
            return Mono.error(new IllegalArgumentException("Refund currency must match payment currency"));
        }
        return Mono.empty();
    }

    /**
     * Returns how much can still be refunded:
     * refundable = captured(SUCCEEDED) - refunds(PENDING+SUCCEEDED)
     */
    private Mono<Long> ensureRefundWithinCaptured(Long paymentId, long requestedAmount) {
        return Mono.zip(
                        sumCapturedSucceeded(paymentId),
                        sumRefundsPendingOrSucceeded(paymentId)
                )
                .map(tuple -> {
                    long captured = tuple.getT1();
                    long alreadyRefunding = tuple.getT2();
                    long refundable = Math.max(0, captured - alreadyRefunding);
                    if (captured <= 0) {
                        throw new IllegalStateException("Nothing captured yet – cannot refund");
                    }
                    return refundable;
                });
    }

    private Mono<Long> sumCapturedSucceeded(Long paymentId) {
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

    private Mono<Long> sumRefundsPendingOrSucceeded(Long paymentId) {
        return db.sql("""
                SELECT COALESCE(SUM(amount_minor),0) AS total
                  FROM refunds
                 WHERE payment_id = :pid AND status IN ('PENDING','SUCCEEDED')
                """)
                .bind("pid", paymentId)
                .map((row, meta) -> {
                    Long v = row.get("total", Long.class);
                    return v == null ? 0L : v;
                })
                .one()
                .defaultIfEmpty(0L);
    }

    /** Use latest successful capture's reference for the provider refund call. */
    private Mono<String> latestSucceededCaptureRef(Long paymentId) {
        return db.sql("""
                SELECT psp_capture_ref
                  FROM captures
                 WHERE payment_id = :pid AND status = 'SUCCEEDED' AND psp_capture_ref IS NOT NULL
                 ORDER BY id DESC
                 LIMIT 1
                """)
                .bind("pid", paymentId)
                .map((row, meta) -> row.get("psp_capture_ref", String.class))
                .one();
    }

    private Mono<RefundEntity> createPendingRefund(PaymentEntity pmt, long amountMinor, String currency, String reason) {
        final Instant now = Instant.now();
        RefundEntity pending = RefundEntity.builder()
                .tenantId(pmt.getTenantId())
                .paymentId(pmt.getId())
                .amountMinor(amountMinor)
                .currencyCode(currency)
                .status(RefundStatus.PENDING)
                .reasonCode(reason)
                .psp(pmt.getPsp())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return refundRepo.save(pending);
    }

    private Mono<RefundEntity> invokeProviderAndFinalize(PaymentEntity pmt,
                                                         RefundEntity refund,
                                                         String captureRef) {
        var result = provider.refund(refund.getAmountMinor(), refund.getCurrencyCode(), captureRef);
        if (result.authorized()) {
            refund.setStatus(RefundStatus.SUCCEEDED);
            refund.setPspRefundRef(result.externalRef());
            refund.setUpdatedAt(Instant.now());

            return refundRepo.save(refund)
                    .flatMap(saved -> ledger.postRefund(pmt, saved)
                            .then(emitRefunded(pmt, saved))
                            .thenReturn(saved));
        } else {
            refund.setStatus(RefundStatus.FAILED);
            refund.setUpdatedAt(Instant.now());
            return refundRepo.save(refund);
        }
    }

    private Mono<Void> emitRefunded(PaymentEntity p, RefundEntity r) {
        var evt = PaymentEvents.Refunded.builder()
                .sagaId(p.getSagaId())
                .tenantId(p.getTenantId())
                .orderId(p.getOrderId())
                .paymentId(p.getId())
                .refundId(r.getId())
                .amountMinor(r.getAmountMinor())
                .currencyCode(r.getCurrencyCode())
                .psp(r.getPsp())
                .pspRefundRef(r.getPspRefundRef())
                .reasonCode(r.getReasonCode())
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

    /* =======================================================
       Utils
       ======================================================= */

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
