package com.github.dimitryivaniuta.payment.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.github.dimitryivaniuta.payment.api.dto.*;
import com.github.dimitryivaniuta.payment.api.mapper.PaymentMappers;
import com.github.dimitryivaniuta.payment.domain.model.AttemptStatus;
import com.github.dimitryivaniuta.payment.domain.model.PaymentAttemptEntity;
import com.github.dimitryivaniuta.payment.domain.model.PaymentEntity;
import com.github.dimitryivaniuta.payment.domain.model.PaymentStatus;
import com.github.dimitryivaniuta.payment.domain.repo.PaymentAttemptRepository;
import com.github.dimitryivaniuta.payment.domain.repo.PaymentRepository;
import com.github.dimitryivaniuta.payment.outbox.OutboxStore;
import com.github.dimitryivaniuta.payment.saga.events.PaymentEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Orchestrates the payment lifecycle.
 * - HTTP authorize uses PaymentAuthorizeRequest (orderId, amount, currency).
 * - Kafka/Orchestrator can still call the overload that takes already-computed fields.
 * - Tenant/user are resolved via SecurityTenantResolver inside the service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepo;
    private final PaymentAttemptRepository attemptRepo;

    // optional domain services; keep if you already have them
    private final CaptureService captureService;
    private final RefundService refundService;
    private final LedgerService ledger;

    private final OutboxStore outbox;
    private final FakePaymentProvider provider;
    private final ObjectMapper om;
    private final TransactionalOperator tx;
    private final SecurityTenantResolver security;

    private static final Set<PaymentStatus> ACTIVE = Set.of(
            PaymentStatus.INITIATED,
            PaymentStatus.AUTHORIZING,
            PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.AUTHORIZED,
            PaymentStatus.CAPTURING
    );

    /* =========================================================================
       Public HTTP API
       ========================================================================= */

    /**
     * HTTP authorize: compute sagaId + amountMinor here from request.
     */
    public Mono<PaymentView> authorize(final PaymentAuthorizeRequest req) {
        requireNonNull(req, "req");

        return security.current().flatMap(ctx -> {
            final String tenantId = ctx.getTenantId();
            final UUID userId = ctx.getUserId();
            final Long orderId = requireNonNull(req.orderId(), "orderId");

            final String currency = (req.currency() == null || req.currency().isBlank())
                    ? "USD" : req.currency().toUpperCase();

            final long amountMinor = toMinor(requireNonNull(req.amount(), "amount"), currency);
            final UUID sagaId = deterministicSagaId(tenantId, orderId);

            return authorizeInternal(tenantId, sagaId, orderId, userId, amountMinor, currency)
                    .map(PaymentMappers::toView);
        });
    }

    /**
     * Controller helpers
     */
    public Flux<PaymentView> listByOrderId(long orderId) {
        return security.current().flatMapMany(ctx ->
                paymentRepo.findAllByTenantAndOrder(ctx.getTenantId(), orderId)
                        .map(PaymentMappers::toView));
    }

    public Mono<PaymentView> getById(long id) {
        return security.current().flatMap(ctx ->
                paymentRepo.findByTenantAndId(ctx.getTenantId(), id)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Payment not found")))
                        .map(PaymentMappers::toView));
    }

    public Mono<CaptureView> capture(long paymentId, PaymentCaptureRequest req) {
        return captureService.capture(paymentId, req);
    }

    public Mono<RefundView> refund(long paymentId, RefundRequest req) {
        return refundService.refund(paymentId, req);
    }

    // Optional test hooks (used by your controller compile complaints)
    public Mono<PaymentView> simulateWebhookSuccess(long paymentId) {
        return security.current().flatMap(ctx ->
                paymentRepo.findByTenantAndId(ctx.getTenantId(), paymentId)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Payment not found")))
                        .flatMap(p -> {
                            p.setStatus(PaymentStatus.CAPTURED);
                            p.setUpdatedAt(Instant.now());
                            return paymentRepo.save(p).flatMap(saved -> emitCaptured(saved)
                                    .thenReturn(PaymentMappers.toView(saved)));
                        })
        );
    }

    public Mono<PaymentView> simulateWebhookFailure(long paymentId) {
        return security.current().flatMap(ctx ->
                paymentRepo.findByTenantAndId(ctx.getTenantId(), paymentId)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Payment not found")))
                        .flatMap(p -> {
                            p.setStatus(PaymentStatus.FAILED);
                            p.setUpdatedAt(Instant.now());
                            return paymentRepo.save(p).flatMap(saved -> emitAuthFailed(saved)
                                    .thenReturn(PaymentMappers.toView(saved)));
                        })
        );
    }

    /* =========================================================================
       Shared internal flow (also usable from Kafka/orchestrator code)
       ========================================================================= */

    public Mono<PaymentView> authorize(
            final String tenantId,
            final UUID sagaId,
            final Long orderId,
            final UUID userId,
            final long amountMinor,
            final String currencyCode
    ) {
        return authorizeInternal(tenantId, sagaId, orderId, userId, amountMinor, currencyCode)
                .map(PaymentMappers::toView);
    }

    private Mono<PaymentEntity> authorizeInternal(
            final String tenantId,
            final UUID sagaId,
            final Long orderId,
            final UUID userId,
            final long amountMinor,
            final String currencyCode
    ) {
        // Idempotency check
        Mono<PaymentEntity> existing =
                paymentRepo.findLatestByTenantAndSaga(tenantId, sagaId)
                        .switchIfEmpty(paymentRepo.findLatestByTenantAndOrder(tenantId, orderId)
                                .filter(p -> ACTIVE.contains(p.getStatus())));

        return existing.switchIfEmpty(Mono.defer(() ->
                tx.transactional(
                        // 1) create payment
                        paymentRepo.save(PaymentEntity.builder()
                                        .tenantId(tenantId)
                                        .sagaId(sagaId)
                                        .orderId(orderId)
                                        .userId(userId)
                                        .amountMinor(amountMinor)
                                        .currencyCode(currencyCode)
                                        .status(PaymentStatus.INITIATED)
                                        .psp("FAKE")
                                        .createdAt(Instant.now())
                                        .updatedAt(Instant.now())
                                        .build())
                                // 2) create first attempt (PENDING)
                                .flatMap(p -> attemptRepo.save(PaymentAttemptEntity.builder()
                                                .tenantId(tenantId)
                                                .paymentId(p.getId())
                                                .attemptNo(1)
                                                .status(AttemptStatus.PENDING)
                                                .psp("FAKE")
                                                .createdAt(Instant.now())
                                                .updatedAt(Instant.now())
                                                .build())
                                        .thenReturn(p))
                                // 3) call provider and finalize
                                .flatMap(this::handleProviderAuthorize)
                )
        )).doOnNext(p -> log.info("authorize(): paymentId={} status={}", p.getId(), p.getStatus()));
    }

    /* =========================================================================
       Provider authorize handling
       ========================================================================= */

    private Mono<PaymentEntity> handleProviderAuthorize(final PaymentEntity payment) {
        // Fake provider returns a sync result
        var res = provider.authorize(payment.getAmountMinor(), payment.getCurrencyCode());

        if (res.authorized()) {
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
            var attempt2 = PaymentAttemptEntity.builder()
                    .tenantId(payment.getTenantId())
                    .paymentId(payment.getId())
                    .attemptNo(2)
                    .status(AttemptStatus.FAILED)
                    .psp("FAKE")
                    .failureCode(res.failureCode())
                    .failureReason(res.failureReason())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
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

    /* =========================================================================
       Outbox events
       ========================================================================= */

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

    private Mono<Void> emitCaptured(PaymentEntity p) {
        var evt = PaymentEvents.Captured.builder()
                .sagaId(p.getSagaId())
                .tenantId(p.getTenantId())
                .orderId(p.getOrderId())
                .paymentId(p.getId())
                .amountMinor(p.getAmountMinor())
                .currencyCode(p.getCurrencyCode())
                .psp(p.getPsp())
                .pspCaptureRef(p.getPspRef())
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
            String json = om.writeValueAsString(payload);
            return outbox.saveEvent(
                    tenantId,
                    sagaId,
                    aggregateType,
                    aggregateId,
                    eventType,
                    String.valueOf(aggregateId),
                    json,
                    Map.of()
            ).then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    /* =========================================================================
       Helpers
       ========================================================================= */

    private static UUID deterministicSagaId(String tenantId, Long orderId) {
        byte[] seed = (tenantId + "|ORDER|" + orderId).getBytes(UTF_8);
        return UUID.nameUUIDFromBytes(seed);
    }

    private static long toMinor(BigDecimal amount, String currencyCode) {
        int fd = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        return amount.movePointRight(fd).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
