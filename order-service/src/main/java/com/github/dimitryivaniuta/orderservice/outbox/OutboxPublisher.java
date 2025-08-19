package com.github.dimitryivaniuta.orderservice.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxKey;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * Reactive, lease-based outbox publisher for a partitioned table:
 *  PRIMARY KEY (id, created_on), with lease_until & attempts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final ObjectMapper objectMapper;
    private final OutboxPublisherProperties props;
    private final KafkaSender<String, byte[]> sender;

//    private final OutboxRepository outbox;   // <— refactored repository
    private final OutboxStore outbox;   // <— refactored repository
    private final DatabaseClient db;         // used only for tenant discovery (optional)

    private volatile Disposable subscription;

    // ----- lifecycle -----

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (subscription != null && !subscription.isDisposed()) return;

        log.info("OutboxPublisher starting: topic={}, batchSize={}, pollInterval={}, leaseDuration={}, tenants={}",
                props.getEventsTopic(), props.getBatchSize(), props.getPollInterval(),
                props.getLeaseDuration(), props.getTenants().isEmpty() ? "<dynamic>" : props.getTenants());

        subscription = Flux.interval(Duration.ZERO, props.getPollInterval())
                .concatMap(tick -> publishOnce())
                .doOnSubscribe(this::onSubscribe)
                .onErrorContinue((ex, sig) -> log.error("OutboxPublisher loop error", ex))
                .subscribe();
    }

    private void onSubscribe(Subscription s) {
        log.info("OutboxPublisher subscribed; polling every {}", props.getPollInterval());
    }

    // ----- main tick -----

    Mono<Void> publishOnce() {
        final Instant now = Instant.now();

        return discoverTenants(now)
                .flatMap(tenant -> processTenant(tenant, now), props.getMaxConcurrentTenants())
                .then();
    }

    // Discover tenants with eligible work, unless tenants are provided in config.
    private Flux<String> discoverTenants(Instant now) {
        if (!props.getTenants().isEmpty()) {
            return Flux.fromIterable(props.getTenants());
        }
        // Dynamic: who has rows with expired/missing lease?
        final String sql = """
            SELECT DISTINCT tenant_id
              FROM outbox
             WHERE lease_until IS NULL OR lease_until < :now_ts
            """;
        return db.sql(sql)
                .bind("now_ts", java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .map((row, md) -> row.get("tenant_id", String.class))
                .all();
    }

    private Mono<Void> processTenant(String tenantId, Instant now) {
        return outbox.claimBatch(tenantId, props.getBatchSize(), props.getLeaseDuration(), now)
                .collectList()
                .flatMap(rows -> {
                    if (rows.isEmpty()) return Mono.empty();

                    // Build correlation keys for delete/retry
                    Map<OutboxKey, OutboxRow> byKey = rows.stream()
                            .collect(toMap(r -> new OutboxKey(r.id(), r.createdOn()), identity()));

                    return sendBatch(rows)
                            .then(outbox.deleteByKeys(byKey.keySet()))
                            .then()
                            .doOnSuccess(v -> log.info("Published & deleted outbox rows: tenant={}, count={}", tenantId, rows.size()))
                            .onErrorResume(ex -> {
                                // Compute a backoff based on max(attempts+1) in the batch (coarse but OK)
                                int maxAttemptsNext = rows.stream()
                                        .map(r -> r.attempts() == null ? 1 : r.attempts() + 1)
                                        .max(Integer::compareTo)
                                        .orElse(1);
                                Duration backoff = computeBackoff(maxAttemptsNext);
                                Instant nextTry = now.plus(backoff);

                                log.warn("Publishing failed for tenant={} batchSize={} attemptsNext={} backoff={}. Cause: {}",
                                        tenantId, rows.size(), maxAttemptsNext, backoff, ex.toString());

                                return outbox.rescheduleForRetry(byKey.keySet(), nextTry).then();
                            });
                })
                .onErrorResume(ex -> {
                    // Per-tenant guardrail: continue other tenants
                    log.error("Tenant processing failed; tenant={} — continuing next tick", tenantId, ex);
                    return Mono.empty();
                });
    }

    // ----- Kafka send -----

    private Mono<Void> sendBatch(List<OutboxRow> rows) {
        final String topic = props.getEventsTopic();
        if (rows.isEmpty()) return Mono.empty();

        List<SenderRecord<String, byte[], OutboxKey>> records = new ArrayList<>(rows.size());

        for (OutboxRow r : rows) {
            String key = chooseKey(r);
            byte[] value = payloadBytes(r.payload());

            ProducerRecord<String, byte[]> pr = new ProducerRecord<>(topic, key, value);

            // headers_json → Kafka headers
            Map<String, String> headers = parseHeadersOrEmpty(r.headersJson());
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                pr.headers().add(new RecordHeader(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            // Always useful:
            if (r.tenantId() != null) {
                pr.headers().add(new RecordHeader("tenant-id", r.tenantId().getBytes(StandardCharsets.UTF_8)));
            }
            if (r.sagaId() != null) {
                pr.headers().add(new RecordHeader("saga-id", r.sagaId().toString().getBytes(StandardCharsets.UTF_8)));
            }
            if (r.eventType() != null) {
                pr.headers().add(new RecordHeader("event-type", r.eventType().getBytes(StandardCharsets.UTF_8)));
            }

            records.add(SenderRecord.create(pr, new OutboxKey(r.id(), r.createdOn())));
        }

        return sender.send(Flux.fromIterable(records))
                .then()
                .doOnSuccess(v -> log.debug("Published outbox records: count={} topic={}", records.size(), topic));
    }

    // ----- utils -----

    private Duration computeBackoff(int attemptsNext) {
        if (attemptsNext < 1) attemptsNext = 1;
        Duration candidate = props.getBaseBackoff().multipliedBy(1L << Math.min(attemptsNext - 1, 10));
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
            log.warn("Invalid outbox headers JSON (ignored)", e);
            return Map.of();
        }
    }
}
