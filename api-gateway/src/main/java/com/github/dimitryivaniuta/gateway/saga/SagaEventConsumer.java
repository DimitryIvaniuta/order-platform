package com.github.dimitryivaniuta.gateway.saga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.github.dimitryivaniuta.gateway.kafka.GatewayKafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.header.Header;
import org.reactivestreams.Subscription;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverRecord;

/**
 * Reactive Kafka consumer that advances Saga state:
 * <ol>
 *   <li>Consumes domain events from configured topics (Reactor-Kafka).</li>
 *   <li>Upserts the {@code saga_status} row (idempotent INSERT ... ON CONFLICT ... UPDATE).</li>
 *   <li>Publishes the latest status to {@link SagaEventBus} for live SSE clients.</li>
 * </ol>
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li><b>Exactly-once semantics are not required</b> for state projection; idempotent upsert is sufficient.</li>
 *   <li>Offsets are <b>acknowledged after successful upsert</b>; commits are batched by Reactor-Kafka.</li>
 *   <li>Payload is parsed as JSON to remain schema-tolerant; only {@code sagaId}, {@code type}, {@code reason}
 *       are required. Tenant/User are read from payload fields if present or from Kafka headers.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaEventConsumer {

    /** In-memory fan-out bus for SSE streaming of saga status updates. */
    private final SagaEventBus bus;

    /** Shared Jackson mapper (configured at app level). */
    private final ObjectMapper objectMapper;

    /** Typed Kafka properties (topics, group id, etc.). */
    private final GatewayKafkaProperties props;

    /** Reactive Kafka receiver subscribed to event topics. */
    private final KafkaReceiver<String, byte[]> receiver;

    /** Reactive R2DBC template used for efficient upsert SQL. */
    private final R2dbcEntityTemplate template;

    /** Active subscription/stream handle for lifecycle management. */
    private volatile Disposable subscription;

    /**
     * Starts the reactive consumption pipeline when the application is ready.
     * Subscribes to the Kafka receiver and processes records sequentially per partition.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (subscription != null && !subscription.isDisposed()) {
            return;
        }

        subscription = receiver
                .receive() // Flux<ReceiverRecord<K,V>>
                .concatMap(this::handleRecord) // backpressure-safe; preserves partition order
                .doOnSubscribe(this::onSubscribe)
                .doOnError(e -> log.error("SagaEventConsumer stream error", e))
                .retryWhen(reactor.util.retry.Retry.backoff( Long.MAX_VALUE, java.time.Duration.ofSeconds(1))
                        .maxBackoff(java.time.Duration.ofMinutes(1))
                        .transientErrors(true))
                .subscribe();
    }

    private void onSubscribe(final Subscription s) {
        log.info("SagaEventConsumer subscribed: groupId={} topics={}",
                props.getGroupId(), props.getEventTopics());
    }

    /**
     * Processes a single Kafka record: parse → map → upsert → SSE → ack.
     */
    private Mono<Void> handleRecord(final ReceiverRecord<String, byte[]> rr) {
        return Mono.defer(() -> {
                    final byte[] value = rr.value();
                    final String topic = rr.topic();

                    final JsonNode json = objectMapper.readTree(value);

                    final UUID sagaId = UUID.fromString(json.required("sagaId").asText());
                    final String type  = json.required("type").asText();
                    final String reason = json.hasNonNull("reason") ? json.get("reason").asText() : null;

                    // Prefer payload fields; fall back to headers if missing
                    final String tenant = textOr(json, "tenantId", header(rr, "tenant-id"));
                    final String user   = textOr(json, "userId", header(rr, "user-id"));

                    final String state = mapEventToState(type);

                    final SagaStatusEntity entity = SagaStatusEntity.builder()
                            .id(sagaId)
                            .tenantId(tenant != null ? tenant : "unknown")
                            .userId(user != null ? user : "unknown")
                            .type("ORDER_CREATE") // or infer from event if you carry type-of-saga
                            .state(state)
                            .reason(reason)
                            .createdAt(OffsetDateTime.now())
                            .updatedAt(OffsetDateTime.now())
                            .build();

                    final String upsert = """
              INSERT INTO saga_status (id, tenant_id, user_id, type, state, reason, created_at, updated_at)
              VALUES ($1, $2, $3, $4, $5, $6, now(), now())
              ON CONFLICT (id) DO UPDATE
                SET state = EXCLUDED.state,
                    reason = EXCLUDED.reason,
                    updated_at = now()
              """;

                    return template.getDatabaseClient()
                            .sql(upsert)
                            .bind("$1", entity.getId())
                            .bind("$2", entity.getTenantId())
                            .bind("$3", entity.getUserId())
                            .bind("$4", entity.getType())
                            .bind("$5", entity.getState())
                            .bind("$6", entity.getReason())
                            .then()
                            .doOnSuccess(v -> {
                                // Fan-out to SSE listeners
                                bus.publish(entity);
                                if (log.isDebugEnabled()) {
                                    log.debug("Upserted saga status sagaId={} state={} topic={} partition={} offset={}",
                                            sagaId, state, topic, rr.receiverOffset().topicPartition(), rr.receiverOffset().offset());
                                }
                            })
                            .doFinally(sig -> rr.receiverOffset().acknowledge()); // ack after processing
                })
                .onErrorResume(ex -> {
                    log.error("Failed to handle event: topic={} partition={} offset={}",
                            rr.topic(), rr.partition(), rr.offset(), ex);
                    // Acknowledge to avoid tight redelivery loop; event streams are idempotent projections.
                    rr.receiverOffset().acknowledge();
                    return Mono.empty();
                });
    }

    /** Safe read of a string field from JSON with fallback. */
    private String textOr(final JsonNode json, final String field, final String fallback) {
        return json.hasNonNull(field) ? json.get(field).asText() : fallback;
    }

    /** Reads a header as UTF-8 string, or null if absent. */
    private String header(final ReceiverRecord<String, byte[]> rr, final String name) {
        Header h = rr.headers().lastHeader(name);
        return (h == null) ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /**
     * Maps an event type (e.g., ORDER_CREATED, INVENTORY_RESERVED) to a high-level Saga state.
     * Adjust as you add more transitions.
     */
    private String mapEventToState(final String type) {
        return switch (type) {
            case "ORDER_CREATED"        -> "STARTED";
            case "INVENTORY_RESERVED"   -> "RESERVED";
            case "PAYMENT_AUTHORIZED",
                 "PAYMENT_CAPTURED"     -> "PAID";
            case "SHIPMENT_DISPATCHED",
                 "ORDER_SHIPPED"        -> "SHIPPED";
            case "ORDER_COMPLETED"      -> "COMPLETED";
            case "ORDER_FAILED",
                 "COMPENSATED",
                 "CANCELLED"            -> "FAILED";
            default                     -> "STARTED";
        };
    }
}
