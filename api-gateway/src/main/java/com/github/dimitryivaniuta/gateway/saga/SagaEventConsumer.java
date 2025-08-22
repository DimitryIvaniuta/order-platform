package com.github.dimitryivaniuta.gateway.saga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.github.dimitryivaniuta.common.kafka.AppKafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
    private final AppKafkaProperties props;

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
                props.getGroupId(), props.getTopics().getEvents().all());
    }

    /**
     * Robust handler:
     * - skips tombstones (null/empty value)
     * - reads type/ids from multiple locations (payload OR headers)
     * - logs & ack on unknown shapes instead of throwing
     * Processes a single Kafka record: parse -> map -> upsert -> SSE -> ack.
     * Uses Mono.fromCallable to handle checked exceptions from Jackson.
     */
    private Mono<Void> handleRecord(final ReceiverRecord<String, byte[]> rr) {
        final byte[] value = rr.value();

        // Tombstone or empty payload (compacted topics, etc.)
        if (value == null || value.length == 0) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping tombstone: topic={} partition={} offset={}",
                        rr.topic(), rr.partition(), rr.offset());
            }
            rr.receiverOffset().acknowledge();
            return Mono.empty();
        }

        return Mono.fromCallable(() -> objectMapper.readTree(value))
                .flatMap(json -> {
                    // --- tolerate different field names / nesting / headers ---
                    String type = coalesce(
                            text(json, "type"),
                            text(json, "eventType"),
                            text(json.at("/payload/type")),
                            header(rr, "event-type"),
                            header(rr, "X-Event-Type")
                    );

                    if (StringUtils.isBlank(type)) {
                        log.warn("Skipping event w/o type: topic={} key={} headers={} payload={}",
                                rr.topic(), rr.key(), headersToMap(rr), safeUtf8(value));
                        return Mono.empty(); // ACK + move on
                    }

                    String tenant = coalesce(
                            text(json, "tenantId"),
                            text(json, "tenant_id"),
                            header(rr, "tenant-id"),
                            header(rr, "X-Tenant-Id")
                    );
                    tenant = normalizeTenant(tenant);

                    String user = coalesce(
                            text(json, "userId"),
                            text(json, "user_id"),
                            header(rr, "user-id"),
                            header(rr, "X-User-Id")
                    );
                    if (StringUtils.isBlank(user)) {
                        user = "unknown";
                    }

                    UUID sagaId = parseUuid(coalesce(
                            text(json, "sagaId"),
                            text(json, "correlationId"),
                            header(rr, "correlation-id"),
                            header(rr, "X-Correlation-Id")
                    ));
                    if (sagaId == null) {
                        log.warn("Skipping event w/o sagaId: topic={} key={} type={} headers={} payload={}",
                                rr.topic(), rr.key(), type, headersToMap(rr), safeUtf8(value));
                        return Mono.empty();
                    }

                    String reason = coalesce(text(json, "reason"), text(json.at("/payload/reason")));
                    String state = mapEventToState(type);

                    var entity = SagaStatusEntity.builder()
                            .id(sagaId)
                            .tenantId(tenant)
                            .userId(user)
                            .type("ORDER_CREATE")   // optional: alter if you differentiate saga types
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

                    var spec = template.getDatabaseClient()
                            .sql(upsert)
                            .bind("id", entity.getId())
                            .bind("tenantId", entity.getTenantId())
                            .bind("userId", entity.getUserId())
                            .bind("type", entity.getType())
                            .bind("state", entity.getState());
                    if (entity.getReason() == null) {
                        spec = spec.bindNull("reason", String.class);
                    } else {
                        spec = spec.bind("reason", entity.getReason());
                    }

                    return  spec.then()
                            .doOnSuccess(v -> {
                                bus.publish(entity);
                                if (log.isDebugEnabled()) {
                                    log.debug("Upserted saga status sagaId={} state={} topic={} partition={} offset={}",
                                            sagaId, state, rr.topic(), rr.partition(), rr.offset());
                                }
                            });
                })
                .onErrorResume(ex -> {
                    log.error("Failed to handle event: topic={} partition={} offset={} key={} headers={} payload={}",
                            rr.topic(), rr.partition(), rr.offset(), rr.key(), headersToMap(rr), safeUtf8(value), ex);
                    // swallow to keep the stream alive
                    return Mono.empty();
                })
                .doFinally(sig -> rr.receiverOffset().acknowledge());
    }

    private static String normalizeTenant(String s) {
        if (StringUtils.isBlank(s)) return "unknown";
        // Handles "{123=[ADMIN, ORDER_READ]}" -> "123"
        if (s.startsWith("{") && s.contains("=")) {
            int b1 = s.indexOf('{');
            int eq = s.indexOf('=');
            if (eq > b1 + 1) return s.substring(b1 + 1, eq).trim();
        }
        // Handles JSON object like {"123":[...]} → "123"
        if (s.startsWith("{") && s.endsWith("}") && s.contains(":")) {
            String inner = s.substring(1, s.length() - 1).trim();
            int colon = inner.indexOf(':');
            if (colon > 1) {
                String key = inner.substring(0, colon).trim();
                if (key.startsWith("\"") && key.endsWith("\"")) key = key.substring(1, key.length() - 1);
                return key;
            }
        }
        return s;
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
        // tolerate a wide range of event names
        String t = type.toUpperCase();
        return switch (t) {
            case "ORDER_CREATE", "ORDER_CREATED" -> "STARTED";
            case "CART_ITEM_ADDED", "CART_ITEM_UPDATED", "CART_ITEM_REMOVED",
                 "DISCOUNT_APPLIED", "SHIPPING_QUOTED" -> "PRICED";
            case "INVENTORY_RESERVED", "RESERVATION_OK" -> "RESERVED";
            case "PAYMENT_AUTHORIZED", "PAYMENT_CAPTURED", "PAYMENT_OK" -> "PAID";
            case "SHIPMENT_DISPATCHED", "ORDER_SHIPPED" -> "SHIPPED";
            case "ORDER_COMPLETED" -> "COMPLETED";
            case "ORDER_FAILED", "COMPENSATED", "CANCELLED", "ORDER_CANCELLED", "PAYMENT_FAILED", "RESERVATION_FAILED" -> "FAILED";
            default -> "STARTED";
        };
    }

    private static String coalesce(String... vals) {
        for (String v : vals) {
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String text(JsonNode n) { // for pointer results
        return (n == null || n.isMissingNode() || n.isNull()) ? null : n.asText();
    }

    private static UUID parseUuid(String s) {
        try { return StringUtils.isBlank(s) ? null : UUID.fromString(s); }
        catch (Exception ignore) { return null; }
    }



    private static Map<String, String> headersToMap(ReceiverRecord<String, byte[]> rr) {
        Map<String, String> m = new HashMap<>();
        rr.headers().forEach(h -> m.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));
        return m;
    }

    private static String safeUtf8(byte[] bytes) {
        try { return new String(bytes, StandardCharsets.UTF_8); }
        catch (Exception e) { return "<binary>"; }
    }


}

