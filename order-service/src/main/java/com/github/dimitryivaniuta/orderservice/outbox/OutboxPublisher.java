package com.github.dimitryivaniuta.orderservice.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxKey;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxRow;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final ObjectMapper objectMapper;
    private final OutboxPublisherProperties props;
    private final KafkaSender<String, byte[]> sender;
    private final OutboxStore outbox;
    private final DatabaseClient db; // only for dynamic tenant discovery

    private volatile Disposable subscription;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        stop(); // avoid double subscription on dev reloads

        log.info("OutboxPublisher starting: topic={}, batchSize={}, pollInterval={}, leaseDuration={}, tenants={}",
                props.getEventsTopic(), props.getBatchSize(), props.getPollInterval(),
                props.getLeaseDuration(), props.hasStaticTenants() ? props.getTenants() : "<dynamic>");

        // One cycle -> delay -> repeat. No hot interval, no backpressure overflow.
        subscription = Mono.defer(this::tickOnceSafe)
                .repeatWhen(r -> r.delayElements(props.getPollInterval()))
                .subscribe(
                        null,
                        ex -> log.error("OutboxPublisher stream terminated", ex),
                        ()  -> log.info("OutboxPublisher stream completed")
                );

        log.info("OutboxPublisher subscribed; polling every {}", props.getPollInterval());
    }

    @PreDestroy
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("OutboxPublisher stopped");
        }
    }

    private Mono<Void> tickOnceSafe() {
        return tickOnce()
                .onErrorResume(ex -> {
                    // swallow so the loop continues; we already log the cause
                    log.error("OutboxPublisher publishOnce error (will retry next tick)", ex);
                    return Mono.empty();
                });
    }

    private Mono<Void> tickOnce() {
        final Instant now = Instant.now();
        return discoverTenants(now)
                .flatMap(tenant -> processTenant(tenant, now), props.getMaxConcurrentTenants())
                .then();
    }

    private Flux<String> discoverTenants(Instant now) {
        if (props.hasStaticTenants()) {
            return Flux.fromIterable(props.getTenants());
        }

        // No parameters at all -> no chance of InParameter here.
        final String sql = """
        SELECT DISTINCT tenant_id
          FROM outbox
         WHERE lease_until IS NULL
            OR lease_until < now()
        """;

        return db.sql(sql)
                .map((row, md) -> row.get("tenant_id", String.class))
                .all()
                .doOnSubscribe(s ->
                        log.trace("discoverTenants: querying dynamic tenants (no binds)"))
                .switchIfEmpty(Mono.defer(() -> {
                    log.trace("discoverTenants: no eligible tenants this tick");
                    return Mono.empty();
                }))
                .doOnError(ex ->
                        log.error("discoverTenants failed (query has no bound parameters)", ex));
    }

    private Mono<Void> processTenant(String tenantId, Instant now) {
        return outbox.claimBatch(tenantId, props.getBatchSize(), props.getLeaseDuration(), now)
                .collectList()
                .flatMap(rows -> {
                    if (rows.isEmpty()) return Mono.empty();

                    var keys = rows.stream()
                            .map(r -> new OutboxKey(r.id(), r.createdOn()))
                            .toList();

                    return sendBatch(rows)
                            .then(outbox.deleteByKeys(keys))
                            .then()
                            .doOnSuccess(v -> log.info("Published & deleted outbox rows: tenant={}, count={}", tenantId, rows.size()))
                            .onErrorResume(ex -> {
                                int attemptsNext = rows.stream()
                                        .map(r -> r.attempts() == null ? 1 : r.attempts() + 1)
                                        .max(Integer::compareTo).orElse(1);

                                Duration backoff = computeBackoff(attemptsNext);
                                Instant nextTry  = now.plus(backoff);

                                log.warn("Publishing failed; tenant={} size={} attemptsNext={} backoff={} cause={}",
                                        tenantId, rows.size(), attemptsNext, backoff, ex.toString());

                                return outbox.rescheduleForRetry(keys, nextTry).then();
                            });
                })
                .onErrorResume(ex -> {
                    log.error("processTenant failed; tenant={}", tenantId, ex);
                    return Mono.empty();
                });
    }

    private Mono<Void> sendBatch(List<OutboxRow> rows) {
        if (rows.isEmpty()) return Mono.empty();

        final String topic = props.getEventsTopic();
        List<SenderRecord<String, byte[], OutboxKey>> recs = new ArrayList<>(rows.size());

        for (OutboxRow r : rows) {
            String key = chooseKey(r);
            byte[] value = r.payload() == null ? new byte[0] : r.payload().getBytes(StandardCharsets.UTF_8);

            ProducerRecord<String, byte[]> pr = new ProducerRecord<>(topic, key, value);

            parseHeaders(r.headersJson()).forEach((k, v) -> {
                if (k != null && v != null) pr.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8)));
            });
            if (r.tenantId()  != null) pr.headers().add(new RecordHeader("tenant-id",  r.tenantId().getBytes(StandardCharsets.UTF_8)));
            if (r.sagaId()    != null) pr.headers().add(new RecordHeader("saga-id",    r.sagaId().toString().getBytes(StandardCharsets.UTF_8)));
            if (r.eventType() != null) pr.headers().add(new RecordHeader("event-type", r.eventType().getBytes(StandardCharsets.UTF_8)));

            recs.add(SenderRecord.create(pr, new OutboxKey(r.id(), r.createdOn())));
        }

        return sender.send(Flux.fromIterable(recs))
                .then()
                .doOnSuccess(v -> log.debug("Published outbox batch size={} topic={}", recs.size(), topic));
    }

    private Duration computeBackoff(int attemptsNext) {
        if (attemptsNext < 1) attemptsNext = 1;
        Duration candidate = props.getBaseBackoff().multipliedBy(1L << Math.min(attemptsNext - 1, 10));
        return candidate.compareTo(props.getMaxBackoff()) > 0 ? props.getMaxBackoff() : candidate;
    }

    private String chooseKey(OutboxRow r) {
        if (r.eventKey() != null && !r.eventKey().isBlank()) return r.eventKey();
        if (r.sagaId()   != null) return r.sagaId().toString();
        if (r.aggregateId() != null) return Long.toString(r.aggregateId());
        return r.aggregateType() != null ? r.aggregateType() : "event";
    }

    private Map<String, String> parseHeaders(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Invalid outbox headers JSON (ignored)", e);
            return Map.of();
        }
    }

}
