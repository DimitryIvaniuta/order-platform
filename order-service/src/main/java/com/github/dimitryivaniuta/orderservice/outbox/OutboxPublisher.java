package com.github.dimitryivaniuta.orderservice.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.dimitryivaniuta.orderservice.web.dto.ClaimedBatch;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.reactivestreams.Subscription;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**
 * Reactive transactional outbox publisher.
 * - Claims NEW rows with SELECT ... FOR UPDATE SKIP LOCKED
 * - Applies a short lease to prevent duplicate work
 * - Publishes to Kafka with Reactor-Kafka
 * - Marks rows PUBLISHED in one UPDATE, or FAILED with exponential backoff
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    /** JSON mapper for headers transform if needed. */
    private final ObjectMapper objectMapper;

    /** Outbox publisher tunables. */
    private final OutboxPublisherProperties props;

    /** Reactor Kafka sender for publishing events. */
    private final KafkaSender<String, byte[]> sender;

    /** Base DB client. */
    private final DatabaseClient db;

    /** R2DBC transactional operator. */
    private final TransactionalOperator tx;

    /** Active subscription handle. */
    private volatile Disposable subscription;

    /**
     * Starts the polling loop after application is ready.
     * Uses a lightweight interval and backpressure-friendly concatMap.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (subscription != null && !subscription.isDisposed()) {
            return;
        }
        log.info("OutboxPublisher starting, topic={}, batchSize={}, pollInterval={}",
                props.getEventsTopic(), props.getBatchSize(), props.getPollInterval());

        subscription = Flux.interval(Duration.ZERO, props.getPollInterval())
                .concatMap(tick -> publishOnce())
                .doOnSubscribe(this::onSubscribe)
                .onErrorContinue((ex, sig) -> log.error("OutboxPublisher loop error", ex))
                .subscribe();
    }

    private void onSubscribe(Subscription s) {
        log.info("OutboxPublisher subscribed with polling every {}", props.getPollInterval());
    }

    /**
     * Claims a batch, leases it, publishes to Kafka, and updates state.
     */
    Mono<Void> publishOnce() {
        return claimBatch()
                .flatMap(batch -> {
                    if (batch.rows().isEmpty()) {
                        return Mono.empty();
                    }
                    // Send to Kafka; if the stream errors, mark the whole batch failed with backoff
                    return sendBatch(batch)
                            .then(markPublished(batch))
                            .onErrorResume(ex -> {
                                log.warn("Publishing batch failed; marking failed. size={} attempts={}",
                                        batch.rows().size(), batch.attemptsAfterLease());
                                return markFailedWithBackoff(batch).then(Mono.error(ex));
                            })
                            .onErrorResume(ex -> {
                                // Swallow at outer level so the loop continues
                                log.error("Batch publish error swallowed to keep loop alive", ex);
                                return Mono.empty();
                            });
                });
    }

    /**
     * Claims up to batchSize NEW rows, applies a lease and increments attempts, then returns the rows.
     * The lease prevents other workers from picking the same rows for a short duration.
     */
    private Mono<ClaimedBatch> claimBatch() {
        final String selectSql = """
        SELECT id, tenant_id, saga_id, aggregate_type, aggregate_id,
               event_type, event_key, payload, headers, attempts
          FROM outbox
         WHERE status = 0
           AND available_at <= now()
         ORDER BY available_at ASC
         LIMIT :limit
         FOR UPDATE SKIP LOCKED
        """;

        // Perform claim in a transaction so the FOR UPDATE lock and lease update are atomic
        return tx.transactional(
                db.sql(selectSql)
                        .bind("limit", props.getBatchSize())
                        .map((row, md) -> new OutboxRow(
                                row.get("id", Long.class),
                                row.get("tenant_id", String.class),
                                row.get("saga_id", java.util.UUID.class),
                                row.get("aggregate_type", String.class),
                                row.get("aggregate_id", Long.class),
                                row.get("event_type", String.class),
                                row.get("event_key", String.class),
                                // payload is JSONB, driver returns String
                                row.get("payload", String.class),
                                row.get("headers", String.class),
                                row.get("attempts", Integer.class) == null ? 0 : row.get("attempts", Integer.class)
                        ))
                        .all()
                        .collectList()
                        .flatMap(rows -> {
                            if (rows.isEmpty()) {
                                return Mono.just(new ClaimedBatch(List.of(), 0));
                            }
                            List<Long> ids = rows.stream().map(OutboxRow::id).toList();
                            final String leaseSql = """
                  UPDATE outbox
                     SET attempts = attempts + 1,
                         available_at = now() + :lease,
                         updated_at = now()
                   WHERE id = ANY(:ids)
                  """;
                            return db.sql(leaseSql)
                                    .bind("lease", props.getLeaseDuration())
                                    .bind("ids", ids.toArray(Long[]::new))
                                    .fetch().rowsUpdated()
                                    .thenReturn(new ClaimedBatch(rows, rows.getFirst().attempts() + 1));
                        })
        );
    }

    /**
     * Sends the claimed batch to Kafka on the configured topic.
     * This uses best-effort batch semantics: either all succeed or we handle a batch-level error.
     */
    private Mono<Void> sendBatch(ClaimedBatch batch) {
        if (batch.rows().isEmpty()) return Mono.empty();

        final String topic = props.getEventsTopic();
        List<SenderRecord<String, byte[], Long>> records = new ArrayList<>(batch.rows().size());

        for (OutboxRow r : batch.rows()) {
            String key = chooseKey(r);
            byte[] value = payloadBytes(r.payload());
            ProducerRecord<String, byte[]> pr = new ProducerRecord<>(topic, key, value);

            // Map headers JSONB to Kafka headers if present
            Map<String, String> headers = parseHeadersOrEmpty(r.headersJson());
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                pr.headers().add(new RecordHeader(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            // Useful correlation headers
            if (r.tenantId() != null) pr.headers().add(new RecordHeader("tenant-id", r.tenantId().getBytes(StandardCharsets.UTF_8)));
            if (r.sagaId() != null) pr.headers().add(new RecordHeader("correlation-id", r.sagaId().toString().getBytes(StandardCharsets.UTF_8)));

            records.add(SenderRecord.create(pr, r.id()));
        }

        return sender.send(Flux.fromIterable(records))
                .then()
                .doOnSuccess(v -> log.debug("Published outbox batch size={} topic={}", records.size(), topic));
    }

    /**
     * Marks all rows in the batch as PUBLISHED.
     */
    private Mono<Void> markPublished(ClaimedBatch batch) {
        if (batch.rows().isEmpty()) return Mono.empty();
        List<Long> ids = batch.rows().stream().map(OutboxRow::id).toList();
        final String sql = """
        UPDATE outbox
           SET status = 1,
               updated_at = now()
         WHERE id = ANY(:ids)
        """;
        return db.sql(sql)
                .bind("ids", ids.toArray(Long[]::new))
                .fetch().rowsUpdated()
                .then()
                .doOnSuccess(v -> log.info("Marked {} outbox rows PUBLISHED", ids.size()));
    }

    /**
     * Marks the rows as FAILED and schedules next attempt using exponential backoff.
     * Backoff grows with attempts and is capped by maxBackoff.
     */
    private Mono<Void> markFailedWithBackoff(ClaimedBatch batch) {
        if (batch.rows().isEmpty()) return Mono.empty();
        List<Long> ids = batch.rows().stream().map(OutboxRow::id).toList();
        Duration backoff = computeBackoff(batch.attemptsAfterLease());
        final String sql = """
        UPDATE outbox
           SET status = 2,
               available_at = now() + :backoff,
               updated_at = now()
         WHERE id = ANY(:ids)
        """;
        return db.sql(sql)
                .bind("backoff", backoff)
                .bind("ids", ids.toArray(Long[]::new))
                .fetch().rowsUpdated()
                .then()
                .doOnSuccess(v -> log.warn("Marked {} outbox rows FAILED; next retry in {}", ids.size(), backoff));
    }

    private Duration computeBackoff(int attempts) {
        if (attempts < 1) attempts = 1;
        Duration candidate = props.getBaseBackoff().multipliedBy(1L << Math.min(attempts - 1, 10));
        return candidate.compareTo(props.getMaxBackoff()) > 0 ? props.getMaxBackoff() : candidate;
    }

    private String chooseKey(OutboxRow r) {
        if (r.eventKey() != null && !r.eventKey().isBlank()) return r.eventKey();
        if (r.sagaId() != null) return r.sagaId().toString();
        if (r.aggregateId() != null) return Long.toString(r.aggregateId());
        return r.aggregateType() != null ? r.aggregateType() : "event";
    }

    private byte[] payloadBytes(String json) {
        return json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, String> parseHeadersOrEmpty(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Invalid outbox headers JSON, ignoring", e);
            return Map.of();
        }
    }

}
