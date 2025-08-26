package com.github.dimitryivaniuta.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.payment.api.dto.*;
import com.github.dimitryivaniuta.payment.api.mapper.PaymentMappers;
import com.github.dimitryivaniuta.payment.domain.model.*;
import com.github.dimitryivaniuta.payment.domain.repo.*;
import com.github.dimitryivaniuta.payment.outbox.OutboxStore;
import com.github.dimitryivaniuta.payment.saga.events.PaymentAuthorizeRequested;
import com.github.dimitryivaniuta.payment.saga.events.PaymentEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Reactive Payment application service.
 * <ul>
 *   <li>Authorizes payments (minor units + ISO currency).</li>
 *   <li>Supports partial/multiple captures and refunds.</li>
 *   <li>Writes double-entry ledger entries per financial movement.</li>
 *   <li>Publishes domain events via transactional outbox.</li>
 *   <li>Idempotency: returns existing payment for same (tenantId,sagaId) OR latest active payment by (tenantId, orderId).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    // --- Repositories ---
    private final PaymentRepository paymentRepo;
    private final PaymentAttemptRepository attemptRepo;
    private final CaptureRepository captureRepo;
    private final RefundRepository refundRepo;
    private final LedgerEntryRepository ledgerRepo;

    // --- Infra ---
    private final OutboxStore outbox;
    private final ObjectMapper om;
    private final DatabaseClient db;
    private final TransactionalOperator tx;

    // --- Provider (replace with real adapter later) ---
    private final FakePaymentProvider provider;

    // Active states used only in application logic (DB doesn't enforce enum sets)
    private static final Set<PaymentStatus> ACTIVE_PAYMENT_STATES = Set.of(
            PaymentStatus.INITIATED,
            PaymentStatus.AUTHORIZING,
            PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.AUTHORIZED,
            PaymentStatus.CAPTURING
    );

    /* =====================================================================================
       AUTHORIZATION
       ===================================================================================== */

    /**
     * Handle authorize command (from Kafka consumer or REST orchestrator).
     * Idempotent by (tenantId, sagaId) and by latest active payment for (tenantId, orderId).
     */
    public Mono<PaymentView> authorize(final PaymentAuthorizeRequested cmd) {
        requireNonNull(cmd, "cmd");
        final String tenantId   = requireNonNull(cmd.tenantId(), "tenantId");
        final UUID   sagaId     = requireNonNull(cmd.sagaId(), "sagaId");
        final Long   orderId    = requireNonNull(cmd.orderId(), "orderId");
        final long   amountMinor= cmd.amountMinor();
        final String currency   = requireNonNull(cmd.currencyCode(), "currencyCode");
        final UUID   userId     = requireNonNull(cmd.userId(), "userId");

        // always work with PaymentEntity; convert to view only at the very end
        Mono<PaymentEntity> idempotent =
                findByTenantAndSaga(tenantId, sagaId)
                        .switchIfEmpty(
                                findLatestByTenantAndOrder(tenantId, orderId)
                                        .filter(p -> ACTIVE_PAYMENT_STATES.contains(p.getStatus()))
                        );

        return idempotent
                .flatMap(existing -> {
                    // idempotent hit path stays PaymentEntity
                    log.info("Authorize() idempotent hit: paymentId={} tenant={} saga={}",
                            existing.getId(), tenantId, sagaId);
                    return Mono.just(existing);
                })
                .switchIfEmpty(
                        // create new + attempt + provider + ledger + outbox → still PaymentEntity
                        Mono.defer(() -> tx.transactional(
                                paymentRepo.save(PaymentEntity.builder()
                                                .tenantId(tenantId)
                                                .sagaId(sagaId)
                                                .orderId(orderId)
                                                .userId(userId)
                                                .amountMinor(amountMinor)
                                                .currencyCode(currency)
                                                .status(PaymentStatus.INITIATED)
                                                .psp("FAKE")
                                                .createdAt(Instant.now())
                                                .updatedAt(Instant.now())
                                                .build())
                                        .flatMap(saved -> attemptRepo.save(PaymentAttemptEntity.builder()
                                                        .tenantId(tenantId)
                                                        .paymentId(saved.getId())
                                                        .attemptNo(1)
                                                        .status(AttemptStatus.PENDING)
                                                        .psp("FAKE")
                                                        .createdAt(Instant.now())
                                                        .updatedAt(Instant.now())
                                                        .build())
                                                .thenReturn(saved))
                                        .flatMap(this::providerAuthorize) // returns Mono<PaymentEntity>
                        ))
                )
                .map(PaymentMappers::toView); // single conversion to PaymentView here
    }

    private Mono<PaymentEntity> providerAuthorize(final PaymentEntity payment) {
        final long amount = payment.getAmountMinor();
        final String currency = payment.getCurrencyCode();

        var res = provider.authorize(amount, currency);
        if (res.authorized()) {
            // SUCCESS
            return attemptRepo.save(PaymentAttemptEntity.builder()
                            .tenantId(payment.getTenantId())
                            .paymentId(payment.getId())
                            .attemptNo(2)
                            .status(AttemptStatus.SUCCEEDED)
                            .psp("FAKE")
                            .pspRef(res.externalRef())
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build())
                    .then(Mono.defer(() -> {
                        payment.setStatus(PaymentStatus.AUTHORIZED);
                        payment.setPsp("FAKE");
                        payment.setPspRef(res.externalRef());
                        payment.setUpdatedAt(Instant.now());
                        return paymentRepo.save(payment);
                    }))
                    .flatMap(saved -> postLedgerAuth(saved)
                            .then(emitAuthorized(saved))
                            .thenReturn(saved));
        } else {
            // FAILED
            return attemptRepo.save(PaymentAttemptEntity.builder()
                            .tenantId(payment.getTenantId())
                            .paymentId(payment.getId())
                            .attemptNo(2)
                            .status(AttemptStatus.FAILED)
                            .psp("FAKE")
                            .failureCode(res.failureCode())
                            .failureReason(res.failureReason())
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build())
                    .then(Mono.defer(() -> {
                        payment.setStatus(PaymentStatus.FAILED);
                        payment.setFailureCode(res.failureCode());
                        payment.setFailureReason(res.failureReason());
                        payment.setUpdatedAt(Instant.now());
                        return paymentRepo.save(payment);
                    }))
                    .flatMap(saved -> emitAuthFailed(saved).thenReturn(saved));
        }
    }

    /* =====================================================================================
       CAPTURE
       ===================================================================================== */

    /**
     * Capture a payment (partial or full). Admin/debug HTTP path or saga-driven in real systems.
     */
    public Mono<CaptureView> capture(long paymentId, CaptureRequest req) {
        final long nowAmount = req.amountMinor() == null ? 0L : req.amountMinor();
        final String reqCurrency = req.currencyCode();

        return paymentRepo.findById(paymentId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Payment not found: " + paymentId)))
                .flatMap(pmt -> {
                    if (pmt.getStatus() != PaymentStatus.AUTHORIZED && pmt.getStatus() != PaymentStatus.CAPTURING) {
                        return Mono.error(new IllegalStateException("Capture requires AUTHORIZED/CAPTURING status"));
                    }
                    // Decide capture amount/currency
                    final long captureAmount = (req.amountMinor() == null ? pmt.getAmountMinor() : nowAmount);
                    final String currency = (reqCurrency == null ? pmt.getCurrencyCode() : reqCurrency);

                    return tx.transactional(
                            captureRepo.save(CaptureEntity.builder()
                                            .tenantId(pmt.getTenantId())
                                            .paymentId(pmt.getId())
                                            .amountMinor(captureAmount)
                                            .currencyCode(currency)
                                            .status(CaptureStatus.PENDING)
                                            .psp(pmt.getPsp())
                                            .createdAt(Instant.now())
                                            .updatedAt(Instant.now())
                                            .build())
                                    .flatMap(capture -> {
                                        // Fake provider: mark succeeded immediately
                                        capture.setStatus(CaptureStatus.SUCCEEDED);
                                        capture.setPspCaptureRef("CAP-" + UUID.randomUUID());
                                        capture.setUpdatedAt(Instant.now());
                                        return captureRepo.save(capture)
                                                .flatMap(savedCap -> {
                                                    // Update payment state
                                                    pmt.setStatus(PaymentStatus.CAPTURED);
                                                    pmt.setUpdatedAt(Instant.now());
                                                    return paymentRepo.save(pmt)
                                                            .then(postLedgerCapture(pmt, savedCap))
                                                            .then(emitCaptured(pmt, savedCap))
                                                            .thenReturn(savedCap);
                                                });
                                    })
                    );
                })
                .map(PaymentMappers::toView);
    }

    /* =====================================================================================
       REFUND
       ===================================================================================== */

    public Mono<RefundView> refund(long paymentId, RefundRequest req) {
        requireNonNull(req.amountMinor(), "amountMinor");
        requireNonNull(req.currencyCode(), "currencyCode");

        return paymentRepo.findById(paymentId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Payment not found: " + paymentId)))
                .flatMap(pmt -> {
                    if (pmt.getStatus() == PaymentStatus.FAILED || pmt.getStatus() == PaymentStatus.CANCELLED) {
                        return Mono.error(new IllegalStateException("Cannot refund a failed/cancelled payment"));
                    }
                    final long amount = req.amountMinor();
                    final String currency = req.currencyCode();

                    return tx.transactional(
                            refundRepo.save(RefundEntity.builder()
                                            .tenantId(pmt.getTenantId())
                                            .paymentId(pmt.getId())
                                            .amountMinor(amount)
                                            .currencyCode(currency)
                                            .status(RefundStatus.PENDING)
                                            .psp(pmt.getPsp())
                                            .reasonCode(req.reasonCode())
                                            .createdAt(Instant.now())
                                            .updatedAt(Instant.now())
                                            .build())
                                    .flatMap(ref -> {
                                        // Fake provider: immediate success
                                        ref.setStatus(RefundStatus.SUCCEEDED);
                                        ref.setPspRefundRef("REF-" + UUID.randomUUID());
                                        ref.setUpdatedAt(Instant.now());
                                        return refundRepo.save(ref)
                                                .flatMap(saved -> postLedgerRefund(pmt, saved)
                                                        .then(emitRefunded(pmt, saved))
                                                        .thenReturn(saved));
                                    })
                    );
                })
                .map(PaymentMappers::toView);
    }

    /* =====================================================================================
       LOOKUPS (idempotency helpers)
       ===================================================================================== */

    private Mono<PaymentEntity> findByTenantAndSaga(String tenantId, UUID sagaId) {
        return db.sql("""
                SELECT id FROM payments
                WHERE tenant_id = :t AND saga_id = :s
                ORDER BY id DESC LIMIT 1
                """)
                .bind("t", tenantId)
                .bind("s", sagaId)
                .map((row, meta) -> row.get("id", Long.class))
                .one()
                .flatMap(paymentRepo::findById);
    }

    private Mono<PaymentEntity> findLatestByTenantAndOrder(String tenantId, Long orderId) {
        return db.sql("""
                SELECT id FROM payments
                WHERE tenant_id = :t AND order_id = :o
                ORDER BY id DESC LIMIT 1
                """)
                .bind("t", tenantId)
                .bind("o", orderId)
                .map((row, meta) -> row.get("id", Long.class))
                .one()
                .flatMap(paymentRepo::findById)
                .onErrorResume(e -> Mono.empty());
    }

    /* =====================================================================================
       LEDGER
       ===================================================================================== */

    /** Post a journal for authorization (AR ↔ PSP_CLEARING). */
    private Mono<Void> postLedgerAuth(PaymentEntity p) {
        final UUID journal = UUID.randomUUID();
        final LocalDate today = LocalDate.now();

        LedgerEntryEntity debitAR = LedgerEntryEntity.builder()
                .tenantId(p.getTenantId())
                .journalId(journal)
                .accountCode("AR")
                .currencyCode(p.getCurrencyCode())
                .debitMinor(p.getAmountMinor())
                .creditMinor(0)
                .paymentId(p.getId())
                .bookingDate(today)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        LedgerEntryEntity creditPSP = LedgerEntryEntity.builder()
                .tenantId(p.getTenantId())
                .journalId(journal)
                .accountCode("PSP_CLEARING")
                .currencyCode(p.getCurrencyCode())
                .debitMinor(0)
                .creditMinor(p.getAmountMinor())
                .paymentId(p.getId())
                .bookingDate(today)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return ledgerRepo.saveAll(Flux.just(debitAR, creditPSP)).then();
    }

    /** Post a journal for capture (PSP_CLEARING ↔ REVENUE). */
    private Mono<Void> postLedgerCapture(PaymentEntity p, CaptureEntity c) {
        final UUID journal = UUID.randomUUID();
        final LocalDate today = LocalDate.now();

        LedgerEntryEntity debitClearing = LedgerEntryEntity.builder()
                .tenantId(p.getTenantId())
                .journalId(journal)
                .accountCode("PSP_CLEARING")
                .currencyCode(c.getCurrencyCode())
                .debitMinor(c.getAmountMinor())
                .creditMinor(0)
                .paymentId(p.getId())
                .captureId(c.getId())
                .bookingDate(today)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        LedgerEntryEntity creditRevenue = LedgerEntryEntity.builder()
                .tenantId(p.getTenantId())
                .journalId(journal)
                .accountCode("REVENUE")
                .currencyCode(c.getCurrencyCode())
                .debitMinor(0)
                .creditMinor(c.getAmountMinor())
                .paymentId(p.getId())
                .captureId(c.getId())
                .bookingDate(today)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return ledgerRepo.saveAll(Flux.just(debitClearing, creditRevenue)).then();
    }

    /** Post a journal for refund (REFUNDS_PAYABLE ↔ PSP_CLEARING). */
    private Mono<Void> postLedgerRefund(PaymentEntity p, RefundEntity r) {
        final UUID journal = UUID.randomUUID();
        final LocalDate today = LocalDate.now();

        LedgerEntryEntity debitRefundsPayable = LedgerEntryEntity.builder()
                .tenantId(p.getTenantId())
                .journalId(journal)
                .accountCode("REFUNDS_PAYABLE")
                .currencyCode(r.getCurrencyCode())
                .debitMinor(r.getAmountMinor())
                .creditMinor(0)
                .paymentId(p.getId())
                .refundId(r.getId())
                .bookingDate(today)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        LedgerEntryEntity creditClearing = LedgerEntryEntity.builder()
                .tenantId(p.getTenantId())
                .journalId(journal)
                .accountCode("PSP_CLEARING")
                .currencyCode(r.getCurrencyCode())
                .debitMinor(0)
                .creditMinor(r.getAmountMinor())
                .paymentId(p.getId())
                .refundId(r.getId())
                .bookingDate(today)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return ledgerRepo.saveAll(Flux.just(debitRefundsPayable, creditClearing)).then();
    }

    /* =====================================================================================
       OUTBOX EVENTS
       ===================================================================================== */

    private Mono<Void> emitAuthorized(PaymentEntity p) {
        var evt = PaymentEvents.Authorized.builder()
                .sagaId(p.getSagaId())
                .tenantId(p.getTenantId())
                .orderId(p.getOrderId())
                .paymentId(p.getId())
                .amountMinor(p.getAmountMinor())
                .currencyCode(p.getCurrencyCode())
                .psp(p.getPsp())
                .pspRef(p.getPspRef())
                .ts(Instant.now())
                .build();
        return emit("payment", p.getId(), evt.type(), p.getTenantId(), p.getSagaId(), evt);
    }

    private Mono<Void> emitAuthFailed(PaymentEntity p) {
        var evt = PaymentEvents.AuthFailed.builder()
                .sagaId(p.getSagaId())
                .tenantId(p.getTenantId())
                .orderId(p.getOrderId())
                .paymentId(p.getId())
                .failureCode(p.getFailureCode())
                .failureReason(p.getFailureReason())
                .ts(Instant.now())
                .build();
        return emit("payment", p.getId(), evt.type(), p.getTenantId(), p.getSagaId(), evt);
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
        return emit("payment", p.getId(), evt.type(), p.getTenantId(), p.getSagaId(), evt);
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
        return emit("payment", p.getId(), evt.type(), p.getTenantId(), p.getSagaId(), evt);
    }

    private Mono<Void> emit(String aggregate,
                            Long aggregateId,
                            String eventType,
                            String tenantId,
                            UUID sagaId,
                            Object payload) {
        try {
            String json = om.writeValueAsString(payload);
            return outbox.saveEvent(
                            tenantId,
                            sagaId,
                            aggregate,
                            aggregateId,
                            eventType,
                            String.valueOf(aggregateId),
                            json,
                            Map.of())
                    .then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    /* =====================================================================================
       Optional convenience methods for controllers
       ===================================================================================== */

    public Mono<PaymentView> getPaymentView(long id) {
        return paymentRepo.findById(id).map(PaymentMappers::toView);
    }

}
