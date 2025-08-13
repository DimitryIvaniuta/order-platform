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
import reactor.util.retry.Retry;

/**
 * Reactive Kafka consumer that advances Saga state.
 * - Consumes events from configured topics.
 * - Upserts the saga_status projection idempotently.
 * - Publishes the latest status to SSE through SagaEventBus.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaEventConsumer {

    /**
     * In-memory fan-out bus for SSE streaming of saga status updates.
     */
    private final SagaEventBus bus;

    /**
     * Shared Jackson object mapper.
     */
    private final ObjectMapper objectMapper;

    /**
     * Typed Kafka properties (topics, group id, etc.).
     */
    private final GatewayKafkaProperties props;

    /**
     * Reactive Kafka receiver subscribed to event topics.
     */
    private final KafkaReceiver<String, byte[]> receiver;

    /**
     * Active subscription/stream handle.
     */
    private volatile Disposable subscription;

    /**
     * Reactive R2DBC template used for efficient upsert SQL.
     */
    private final R2dbcEntityTemplate template;

    /**
     * Start the pipeline when the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (subscription != null && !subscription.isDisposed()) {
            return;
        }

        subscription = receiver
                .receive()
                .concatMap(this::handleRecord) // preserves partition order
                .doOnSubscribe(this::onSubscribe)
                .doOnError(e -> log.error("SagaEventConsumer stream error", e))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1))
                        .maxBackoff(java.time.Duration.ofMinutes(1))
                        .transientErrors(true))
                .subscribe();
    }

    private void onSubscribe(final Subscription s) {
        log.info("SagaEventConsumer subscribed: groupId={} topics={}",
                props.getGroupId(), props.getEventTopics());
    }

    /**
     * Processes a single Kafka record: parse -> map -> upsert -> SSE -> ack.
     * Uses Mono.fromCallable to handle checked exceptions from Jackson.
     */
    private Mono<Void> handleRecord(final ReceiverRecord<String, byte[]> rr) {
        final byte[] value = rr.value();
        final String topic = rr.topic();

        return Mono.fromCallable(() -> objectMapper.readTree(value))
                .flatMap(json -> {
                    UUID sagaId = UUID.fromString(requiredText(json, "sagaId"));
                    String type = requiredText(json, "type");
                    String reason = optionalText(json, "reason");

                    String tenant = optionalText(json, "tenantId");
                    if (tenant == null) {
                        tenant = header(rr, "tenant-id");
                    }
                    String user = optionalText(json, "userId");
                    if (user == null) {
                        user = header(rr, "user-id");
                    }

                    String state = mapEventToState(type);

                    var entity = SagaStatusEntity.builder()
                            .id(sagaId)
                            .tenantId(tenant != null ? tenant : "unknown")
                            .userId(user != null ? user : "unknown")
                            .type("ORDER_CREATE")
                            .state(state)
                            .reason(reason)
                            .createdAt(OffsetDateTime.now())
                            .updatedAt(OffsetDateTime.now())
                            .build();

                    final String upsert = """
                            INSERT INTO saga_status (id, tenant_id, user_id, type, state, reason, created_at, updated_at)
                            VALUES (:id, :tenantId, :userId, :type, :state, :reason, now(), now())
                            ON CONFLICT (id) DO UPDATE
                              SET state = EXCLUDED.state,
                                  reason = EXCLUDED.reason,
                                  updated_at = now()
                            """;

                    return template.getDatabaseClient()
                            .sql(upsert)
                            .bind("id", entity.getId())
                            .bind("tenantId", entity.getTenantId())
                            .bind("userId", entity.getUserId())
                            .bind("type", entity.getType())
                            .bind("state", entity.getState())
                            .bind("reason", entity.getReason())
                            .then()
                            .doOnSuccess(v -> {
                                bus.publish(entity);
                                if (log.isDebugEnabled()) {
                                    log.debug("Upserted saga status sagaId={} state={} topic={} partition={} offset={}",
                                            sagaId, state, topic, rr.receiverOffset().topicPartition(), rr.receiverOffset().offset());
                                }
                            });
                })
                .onErrorResume(ex -> {
                    log.error("Failed to handle event: topic={} partition={} offset={}",
                            rr.topic(), rr.partition(), rr.offset(), ex);
                    return Mono.empty();
                })
                .doFinally(sig -> rr.receiverOffset().acknowledge());
    }

    /**
     * Required string field reader that throws if missing or blank.
     */
    private String requiredText(final JsonNode json, final String field) {
        JsonNode n = json.get(field);
        if (n == null || n.isNull() || n.asText().isBlank()) {
            throw new IllegalArgumentException("Missing or blank field: " + field);
        }
        return n.asText();
    }

    /**
     * Optional string field reader that returns null when absent.
     */
    private String optionalText(final JsonNode json, final String field) {
        JsonNode n = json.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    /**
     * Reads a Kafka header as UTF-8 string, or null if absent.
     */
    private String header(final ReceiverRecord<String, byte[]> rr, final String name) {
        Header h = rr.headers().lastHeader(name);
        return (h == null) ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /**
     * Maps event type to a high-level saga state.
     */
    private String mapEventToState(final String type) {
        return switch (type) {
            case "ORDER_CREATED" -> "STARTED";
            case "INVENTORY_RESERVED" -> "RESERVED";
            case "PAYMENT_AUTHORIZED", "PAYMENT_CAPTURED" -> "PAID";
            case "SHIPMENT_DISPATCHED", "ORDER_SHIPPED" -> "SHIPPED";
            case "ORDER_COMPLETED" -> "COMPLETED";
            case "ORDER_FAILED", "COMPENSATED", "CANCELLED" -> "FAILED";
            default -> "STARTED";
        };
    }
}

