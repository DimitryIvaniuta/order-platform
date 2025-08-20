package com.github.dimitryivaniuta.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.orderservice.outbox.OutboxStore;
import com.github.dimitryivaniuta.orderservice.saga.msg.OrderCreateCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Produces the Order "create" command by writing a row into the transactional outbox.
 * The OutboxPublisher will publish the row to Kafka (topic configured via OutboxPublisherProperties).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCommandProducer {

    private static final String AGGREGATE_TYPE = "ORDER";
    private static final String EVENT_TYPE_CREATE = "ORDER_CREATE";

    private final OutboxStore outbox;
    private final ObjectMapper objectMapper;

    /**
     * Enqueue a start-of-saga command for a newly created order.
     *
     * @param tenantId      tenant identifier (non-null, non-blank)
     * @param orderId       DB id of the order (non-null)
     * @param userId        UUID of the requester (non-null)
     * @param correlationId optional correlation id to propagate
     * @param amount        order total (non-null)
     */
    public Mono<Void> publishCreate(
            final String tenantId,
            final Long orderId,
            final UUID userId,
            final String correlationId,
            final BigDecimal amount
    ) {
        // Validate inputs early (fail fast)
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(userId,   "userId");
        Objects.requireNonNull(amount,   "amount");

        // Deterministic saga id per (tenant, aggregate, id) to keep partitioning stable
        final UUID sagaId = UUID.nameUUIDFromBytes(
                (tenantId + "|" + AGGREGATE_TYPE + "|" + orderId).getBytes(StandardCharsets.UTF_8)
        );

        // Build command payload (keeps your existing DTO schema)
        final OrderCreateCommand cmd = new OrderCreateCommand(
                sagaId, tenantId, orderId, userId, amount, Instant.now()
        );

        // Serialize once; wrap checked exception into reactive error
        final String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(cmd);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("Failed to serialize OrderCreateCommand", e));
        }

        // Minimal, consistent headers for tracing/tenancy
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("tenant-id", tenantId);
        headers.put("user-id", userId.toString());
        headers.put("event-type", EVENT_TYPE_CREATE);
        if (correlationId != null && !correlationId.isBlank()) {
            headers.put("correlation-id", correlationId);
        }

        // Use orderId as event_key (good partitioning); OutboxPublisher will choose the Kafka key
        return outbox.insertRow(
                        tenantId,
                        sagaId,
                        AGGREGATE_TYPE,
                        orderId,                 // may be null for other events; here it's present
                        EVENT_TYPE_CREATE,
                        String.valueOf(orderId), // event_key
                        payloadJson,
                        headers
                )
                .doOnSuccess(r -> {
                    if (log.isDebugEnabled()) {
                        log.debug("Enqueued ORDER_CREATE into outbox: tenant={} sagaId={} orderId={}",
                                tenantId, sagaId, orderId);
                    }
                })
                .doOnError(err -> log.error("OrderCommandProducer publishCreate error", err))
                .then(); // we only care that it’s queued; OutboxPublisher handles Kafka
    }
}
