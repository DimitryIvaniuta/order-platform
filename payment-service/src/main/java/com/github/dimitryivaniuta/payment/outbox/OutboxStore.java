package com.github.dimitryivaniuta.payment.outbox;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Reactive Outbox persistence for the payment-service.
 * Writes rows inside the caller's transaction; publishing is done asynchronously by OutboxPublisher.
 */
public interface OutboxStore {

    /**
     * Persist one domain event into the outbox (within the caller's tx).
     *
     * @param tenantId       tenant partition
     * @param sagaId         correlation id (saga)
     * @param aggregateType  e.g. "payment"
     * @param aggregateId    id of aggregate or null
     * @param eventType      e.g. "PAYMENT_AUTHORIZED"
     * @param eventKey       Kafka key (string) or null
     * @param payload        JSON body
     * @param headers        optional headers (string key/value)
     */
    Mono<OutboxRow> saveEvent(
            String tenantId,
            UUID sagaId,
            String aggregateType,
            Long aggregateId,
            String eventType,
            String eventKey,
            String payload,
            Map<String, String> headers
    );

    /**
     * Lease a batch for a tenant using SELECT ... FOR UPDATE SKIP LOCKED and bump attempts.
     * Returns the leased rows (already updated).
     */
    Flux<OutboxRow> leaseBatchForTenant(String tenantId, int batchSize, Duration leaseDuration);

    /** Optional helper to clear lease flags after publish (rarely needed if you hard-delete). */
    Mono<Integer> markPublished(Collection<OutboxKey> keys);

    /** Hard-delete published rows (recommended to keep partitions small). */
    Mono<Integer> deleteByKeys(Collection<OutboxKey> keys);
}
