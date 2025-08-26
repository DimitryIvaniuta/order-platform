package com.github.dimitryivaniuta.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.payment.api.dto.CaptureRequest;
import com.github.dimitryivaniuta.payment.api.dto.CaptureView;
import com.github.dimitryivaniuta.payment.api.dto.PaymentView;
import com.github.dimitryivaniuta.payment.api.dto.RefundRequest;
import com.github.dimitryivaniuta.payment.api.dto.RefundView;
import com.github.dimitryivaniuta.payment.api.mapper.PaymentMappers;
import com.github.dimitryivaniuta.payment.domain.model.AttemptStatus;
import com.github.dimitryivaniuta.payment.domain.model.PaymentAttemptEntity;
import com.github.dimitryivaniuta.payment.domain.model.PaymentEntity;
import com.github.dimitryivaniuta.payment.domain.model.PaymentStatus;
import com.github.dimitryivaniuta.payment.domain.repo.PaymentAttemptRepository;
import com.github.dimitryivaniuta.payment.domain.repo.PaymentRepository;
import com.github.dimitryivaniuta.payment.outbox.OutboxStore;
import com.github.dimitryivaniuta.payment.saga.events.PaymentAuthorizeRequested;
import com.github.dimitryivaniuta.payment.saga.events.PaymentEvents;
import com.github.dimitryivaniuta.payment.service.FakePaymentProvider;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import static java.util.Objects.requireNonNull;

/**
 * Application service for the Payment aggregate.
 *
 * Responsibilities
 * - Handle authorization command (idempotent, double-entry ledger, outbox events)
 * - Delegate capture/refund to specialized services
 * - Provide simple read helpers for controllers
 *
 * Notes
 * - All money is in minor units (long).
 * - DB does not enforce enum value sets; enums are enforced at the application level.
 * - Exactly-once effect for events is achieved by idempotent consumers; we publish via outbox (at-least-once).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    /* ==========================================================
       Dependencies
       ========================================================== */
    private final PaymentRepository paymentRepo;
    private final PaymentAttemptRepository attemptRepo;
    private final CaptureService captureService;
    private final RefundService refundService;
    private final LedgerService ledger;
    private final OutboxStore outbox;
    private final FakePaymentProvider provider;
    private final ObjectMapper objectMapper;
    private final TransactionalOperator tx;
    private final DatabaseClient db;

    /* ==========================================================
       Idempotency helpers (in code; DB doesn't know enum sets)
       ========================================================== */
    private static final Set<PaymentStatus> ACTIVE_PAYMENT_STATES = Set.of(
            PaymentStatus.INITIATED,
            PaymentStatus.AUTHORIZING,
            PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.AUTHORIZED,
            PaymentStatus.CAPTURING
    );

    /* ==========================================================
       Public API
       ========================================================== */

    /**
     * Authorize a payment (command from Kafka or internal orchestrator).
     * Idempotent by (tenantId, sagaId); if not found, falls back to latest active for (tenantId, orderId).
     */
    public Mono<PaymentView> authorize(final PaymentAuthorizeRequested cmd) {
        requireNonNull(cmd, "cmd");
        final String tenantId = requireNonNull(cmd.tenantId(), "tenantId");
        final UUID sagaId     = requireNonNull(cmd.sagaId(), "sagaId");
        final Long orderId    = requireNonNull(cmd.orderId(), "orderId");
        final long amount     = requireNonNull(cmd.amountMinor(), "amountMinor");
        final String currency = requireNonNull(cmd.currencyCode(), "currencyCode");
        final UUID userId     = requireNonNull(cmd.userId(), "userId");

        Mono<PaymentEntity> existingMono =
                findByTenantAndSaga(tenantId, sagaId)
                        .switchIfEmpty(findLatestByTenantAndOrder(tenantId, orderId)
                                .filter(p -> ACTIVE_PAYMENT_STATES.contains(p.getStatus())));

        return existingMono
                .flatMap(existing -> {
                    log.info("authorize(): idempotent hit paymentId={} tenant={} saga={}",
                            existing.getId(), tenantId, sagaId);
                    return Mono.just(existing);
                })
                .switchIfEmpty(
                        Mono.defer(() -> tx.transactional(
                                // 1) Create payment (INITIATED)
                                paymentRepo.save(PaymentEntity.builder()
                                                .tenantId(tenantId)
                                                .sagaId(sagaId)
                                                .orderId(orderId)
                                                .userId(userId)
                                                .amountMinor(amount)
                                                .currencyCode(currency)
                                                .status(PaymentStatus.INITIATED)
                                                .psp("FAKE")
                                                .createdAt(Instant.now())
                                                .updatedAt(Instant.now())
                                                .build())
                                        // 2) Create initial attempt (PENDING)
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
                                        // 3) Call provider & finalize
                                        .flatMap(this::handleProviderAuthorize)
                        )))
                .map(PaymentMappers::toView);
    }

    /**
     * Convenience wrapper delegating to {@link CaptureService}.
     */
    public Mono<CaptureView> capture(long paymentId, CaptureRequest req) {
        return captureService.capture(paymentId, req);
    }

    /**
     * Convenience wrapper delegating to {@link RefundService}.
     */
    public Mono<RefundView> refund(long paymentId, RefundRequest req) {
        return refundService.refund(paymentId, req);
    }

    /**
     * Read helper for controllers.
     */
    public Mono<PaymentView> getPaymentView(long id) {
        return paymentRepo.findById(id).map(PaymentMappers::toView);
    }

    /* ==========================================================
       Internals — Provider authorize flow
       ========================================================== */

    private Mono<PaymentEntity> handleProviderAuthorize(final PaymentEntity payment) {
        // Fake provider: sync call with deterministic rules
        var res = provider.authorize(payment.getAmountMinor(), payment.getCurrencyCode());

        if (res.authorized()) {
            // Attempt succeeded
            var attempt2 = PaymentAttemptEntity.builder()
                    .tenantId(payment.getTenantId())
                    .paymentId(payment.getId())
                    .attemptNo(2)
                    .status(AttemptStatus.SUCCEEDED)
                    .psp("FAKE")
                    .pspRef(res.externalRef())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            payment.setStatus(PaymentStatus.AUTHORIZED);
            payment.setPsp("FAKE");
            payment.setPspRef(res.externalRef());
            payment.setUpdatedAt(Instant.now());

            return attemptRepo.save(attempt2)
                    .then(paymentRepo.save(payment))
                    .flatMap(saved -> ledger.postAuthorization(saved).thenReturn(saved))
                    .flatMap(saved -> emitAuthorized(saved).thenReturn(saved));
        } else {
            // Attempt failed
            var attempt2 = PaymentAttemptEntity.builder()
                    .tenantId(payment.getTenantId())
                    .paymentId(payment.getId())
                    .attemptNo(2)
                    .status(AttemptStatus.FAILED)
                    .psp("FAKE")
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .failureCode(res.failureCode())
                    .failureReason(res.failureReason())
                    .build();

            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureCode(res.failureCode());
            payment.setFailureReason(res.failureReason());
            payment.setUpdatedAt(Instant.now());

            return attemptRepo.save(attempt2)
                    .then(paymentRepo.save(payment))
                    .flatMap(saved -> emitAuthFailed(saved).thenReturn(saved));
        }
    }

    /* ==========================================================
       Outbox events
       ========================================================== */

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

    private Mono<Void> emit(String aggregateType,
                            Long aggregateId,
                            String eventType,
                            String tenantId,
                            UUID sagaId,
                            Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return outbox.saveEvent(
                            tenantId,
                            sagaId,
                            aggregateType,
                            aggregateId,
                            eventType,
                            String.valueOf(aggregateId),
                            json,
                            Map.of()
                    )
                    .then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    /* ==========================================================
       Lookups (idempotency)
       ========================================================== */

    private Mono<PaymentEntity> findByTenantAndSaga(String tenantId, UUID sagaId) {
        return db.sql("""
                SELECT id FROM payments
                WHERE tenant_id = :t AND saga_id = :s
                ORDER BY id DESC
                LIMIT 1
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
                ORDER BY id DESC
                LIMIT 1
                """)
                .bind("t", tenantId)
                .bind("o", orderId)
                .map((row, meta) -> row.get("id", Long.class))
                .one()
                .flatMap(paymentRepo::findById)
                .onErrorResume(e -> Mono.empty());
    }
}
