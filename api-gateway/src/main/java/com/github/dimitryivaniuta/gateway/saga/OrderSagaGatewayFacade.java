package com.github.dimitryivaniuta.gateway.saga;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.github.dimitryivaniuta.gateway.dto.OrderLine;
import com.github.dimitryivaniuta.gateway.saga.msg.OrderCreateCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Facade that starts Order-related Sagas from HTTP requests by:
 * <ol>
 *   <li>Seeding a {@link SagaStatusEntity} row (state {@code STARTED}) in the gateway DB,</li>
 *   <li>Publishing a Kafka command (e.g., {@code order.command.create.v1}) carrying the correlation id,</li>
 *   <li>Returning the generated {@code sagaId} to the caller for subsequent polling/SSE streaming.</li>
 * </ol>
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li><strong>Global correlation:</strong> {@code sagaId} is generated in the application (UUID),
 *       reused as the Kafka key and DB primary key for strong correlation and partition ordering.</li>
 *   <li><strong>Reactive-only:</strong> non-blocking persistence and Kafka I/O using Reactor.</li>
 *   <li><strong>SSE immediacy:</strong> after seeding, the initial status is published to {@link SagaEventBus}
 *       so subscribers see the state instantly, even before downstream services emit events.</li>
 *   <li><strong>Failure path:</strong> if the command publish fails, the status is updated to {@code FAILED}
 *       with a reason, and the error is propagated to the caller.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSagaGatewayFacade {

    /** In-memory fan-out for live saga updates (SSE). */
    private final SagaEventBus bus;

    /** Kafka producer for emitting saga commands. */
    private final SagaProducer producer;

    /** Reactive repository for persisting saga status snapshots. */
    private final SagaStatusRepository sagaRepo;

    /**
     * Starts an "Order Create" saga.
     *
     * @param tenantId   tenant identifier (authorization already enforced upstream)
     * @param userId     user subject (from JWT {@code sub})
     * @param customerId FK of the customer placing the order
     * @param lines      order lines (non-empty)
     * @param total      monetary total (pre-computed by controller)
     * @param headers    transport/correlation headers (e.g., correlation-id, idempotency-key)
     * @return a {@link Mono} emitting the generated {@code sagaId} when the command is successfully sent
     */
    public Mono<UUID> startOrderCreate(
            final String tenantId,
            final String userId,
            final Long customerId,
            final List<OrderLine> lines,
            final BigDecimal total,
            final Map<String, String> headers) {

        // Basic args validation (fail fast, reactive-friendly).
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        if (lines.isEmpty()) {
            return Mono.error(new IllegalArgumentException("lines must not be empty"));
        }
        Objects.requireNonNull(total, "total must not be null");
        Objects.requireNonNull(headers, "headers must not be null");

        // NOTE: For best index locality, prefer UUIDv7. Swap generator here if you add a v7 lib.
        final UUID sagaId = UUID.randomUUID();

        // Prepare initial DB status (STARTED).
        final SagaStatusEntity seed = SagaStatusEntity.builder()
                .id(sagaId)
                .tenantId(tenantId)
                .userId(userId)
                .type("ORDER_CREATE")
                .state("STARTED")
                .reason(null)
                .build();

        // Build command payload for downstream services.

        final OrderCreateCommand cmd = OrderCreateCommand.builder()
                .sagaId(sagaId)
                .tenantId(tenantId)
                .userId(userId)
                .customerId(customerId)
                .totalAmount(total)
                .lines(lines).build();

        // 1) Persist initial status -> 2) publish to SSE -> 3) send command -> 4) return sagaId
        return sagaRepo.save(seed)
                .doOnSuccess(saved -> {
                    // Push the initial state to SSE so clients instantly see "STARTED".
                    try {
                        bus.publish(saved);
                    } catch (Exception e) {
                        log.warn("Failed to publish initial saga state to SSE sagaId={}", sagaId, e);
                    }
                })
                .then(producer.sendOrderCreate(cmd, headers))
                .thenReturn(sagaId)
                .doOnSuccess(id -> log.info("Started OrderCreate saga sagaId={} tenant={} user={}", id, tenantId, userId))
                .onErrorResume(ex -> {
                    // Mark saga as FAILED if publishing fails, then rethrow.
                    log.error("Failed to start OrderCreate saga sagaId={} tenant={} user={}", sagaId, tenantId, userId, ex);
                    final SagaStatusEntity failed = SagaStatusEntity.builder()
                            .id(sagaId)
                            .tenantId(tenantId)
                            .userId(userId)
                            .type("ORDER_CREATE")
                            .state("FAILED")
                            .reason("command_publish_failed: " + ex.getClass().getSimpleName())
                            .build();
                    return sagaRepo.save(failed)
                            .doOnSuccess(bus::publish)
                            .then(Mono.error(ex));
                });
    }
}
