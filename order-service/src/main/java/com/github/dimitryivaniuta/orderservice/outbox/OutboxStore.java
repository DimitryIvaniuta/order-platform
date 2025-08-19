package com.github.dimitryivaniuta.orderservice.outbox;

import com.github.dimitryivaniuta.orderservice.web.dto.OutboxKey;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxRow;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

//@Transactional(propagation = Propagation.MANDATORY)
public interface OutboxStore {

    /** Composite PK for partitioned table. */
//    record OutboxKey(Long id, LocalDate createdOn) {}

    /** Row mapped from SELECT/RETURNING. */
//    record OutboxRow(
//            Long id,
//            LocalDate createdOn,
//            String tenantId,
//            UUID sagaId,
//            String aggregateType,
//            Long aggregateId,
//            String eventType,
//            String eventKey,
//            String payload,        // TEXT
//            String headersJson,    // TEXT
//            Integer attempts,
//            OffsetDateTime leaseUntil,
//            OffsetDateTime createdAt,
//            OffsetDateTime updatedAt
//    ) {}

    // --- operations ---

    Mono<OutboxRow> insertRow(
            String tenantId,
            UUID sagaId,                 // null => derive deterministic from tenant+aggregateType+eventKey
            String aggregateType,
            Long aggregateId,
            String eventType,
            String eventKey,
            String payloadJson,
            Map<String, String> headers
    );

    Flux<OutboxRow> claimBatch(String tenantId, int batchSize, Duration leaseDuration, Instant now);

    Mono<Long> rescheduleForRetry(OutboxKey key, Instant nextTry);
    Mono<Long> rescheduleForRetry(Collection<OutboxKey> keys, Instant nextTry);

    Mono<Long> releaseExpiredLeases(String tenantId, Instant now);

    Mono<Long> deleteByKey(OutboxKey key);
    Mono<Long> deleteByKeys(Collection<OutboxKey> keys);
}
