package com.github.dimitryivaniuta.payment.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.common.outbox.OutboxPublisherProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.context.event.ApplicationReadyEvent;
//import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Periodically leases outbox rows per tenant, publishes to Kafka, then deletes them.
 * Fault-tolerant with onErrorContinue; safe to run with multiple instances.
 */
@Slf4j
@Component
@RequiredArgsConstructor
//@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxPublisher {

    private final OutboxStore store;
    private final KafkaSender<String, byte[]> sender;
    private final ObjectMapper om;
    private final OutboxPublisherProperties props;

    private Disposable loop;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (loop != null && !loop.isDisposed()) return;

        loop = Flux.interval(props.getPollInterval())
                .flatMap(tick -> tenantsToScan(), props.getMaxConcurrentTenants())
                .flatMap(this::publishTenantBatch, props.getMaxConcurrentTenants())
                .onErrorContinue((e, o) -> log.error("Outbox publish loop error (ignored)", e))
                .subscribe();
        log.info("OutboxPublisher started: topic={}, pollInterval={}", props.getEventsTopic(), props.getPollInterval());
    }

    @SuppressWarnings("unused")
    public void stop() {
        if (loop != null) loop.dispose();
    }

    private Flux<String> tenantsToScan() {
        if (props.hasStaticTenants()) return Flux.fromIterable(props.getTenants());
        // Replace with a distinct tenant scan if you persist tenants; default single-tenant
        return Flux.just("default");
    }

    private Flux<Void> publishTenantBatch(String tenantId) {
        Duration lease = props.getLeaseDuration();
        return store.leaseBatchForTenant(tenantId, props.getBatchSize(), lease)
                .buffer(100) // send in micro-batches to increase throughput
                .flatMap(this::sendBatch, 1)
                .onErrorResume(e -> {
                    log.warn("Tenant batch failed, tenant={}", tenantId, e);
                    return Flux.empty();
                });
    }

    private Flux<Void> sendBatch(List<OutboxRow> rows) {
        if (rows == null || rows.isEmpty()) return Flux.empty();

        var sendFlux = Flux.fromIterable(rows)
                .map(this::toSenderRecord)
                .collectList()
                .flatMapMany(records -> sender.send(Flux.fromIterable(records)));

        return sendFlux
                .publishOn(Schedulers.parallel())
                .doOnNext(result -> {
                    OutboxKey key = result.correlationMetadata();
                    log.debug("Outbox sent id={} co={} topic={}", key.id(), key.createdOn(), props.getEventsTopic());
                })
                .collectList()
                .flatMapMany(results -> {
                    List<OutboxKey> keys = results.stream()
                            .map(r -> (OutboxKey) r.correlationMetadata())
                            .toList();
                    return store.deleteByKeys(keys).thenMany(Flux.empty());
                });
    }

    private SenderRecord<String, byte[], OutboxKey> toSenderRecord(OutboxRow row) {
        String topic = props.getEventsTopic();
        String key = row.eventKey();
        byte[] value = row.payload().getBytes(StandardCharsets.UTF_8);

        var record = new ProducerRecord<String, byte[]>(topic, key, value);

        // Add typed headers
        record.headers().add(new RecordHeader("event-type", bytes(row.eventType())));
        record.headers().add(new RecordHeader("tenant-id", bytes(row.tenantId())));
        if (row.aggregateType() != null) record.headers().add(new RecordHeader("aggregate-type", bytes(row.aggregateType())));
        if (row.aggregateId() != null)   record.headers().add(new RecordHeader("aggregate-id", bytes(String.valueOf(row.aggregateId()))));

        // User-provided headers
        parseHeaders(row.headersJson()).forEach((k, v) -> {
            if (k != null && v != null) record.headers().add(new RecordHeader(k, bytes(v)));
        });

        return SenderRecord.create(record, new OutboxKey(row.id(), row.createdOn()));
    }

    private Map<String, String> parseHeaders(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return om.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse outbox headers_json; sending without custom headers", e);
            return Map.of();
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
