package com.github.dimitryivaniuta.payment.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Postgres/R2DBC implementation of the partitioned outbox.
 * Uses a WRITE-then-LEASE pattern with SKIP LOCKED for safe concurrent publishers.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OutboxStoreImpl implements OutboxStore {

    private final DatabaseClient db;
    private final ObjectMapper om;

    @Override
    public Mono<OutboxRow> saveEvent(
            String tenantId,
            UUID sagaId,
            String aggregateType,
            Long aggregateId,
            String eventType,
            String eventKey,
            String payload,
            Map<String, String> headers
    ) {
        final boolean hasAggId = aggregateId != null;
        final boolean hasKey = eventKey != null && !eventKey.isBlank();

        final String headersJson;
        try {
            headersJson = (headers == null || headers.isEmpty()) ? null : om.writeValueAsString(headers);
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("Invalid headers map", e));
        }

        final String sql = """
            INSERT INTO outbox
              (tenant_id, saga_id, aggregate_type, aggregate_id, event_type, event_key,
               payload, headers_json, attempts, lease_until)
            VALUES
              (:tenant_id, :saga_id, :aggregate_type,
               %s, :event_type, %s,
               :payload, :headers_json, 0, NULL)
            RETURNING id, created_on, tenant_id, saga_id, aggregate_type, aggregate_id,
                      event_type, event_key, payload, headers_json, attempts, lease_until,
                      created_at, updated_at
            """.formatted(hasAggId ? ":aggregate_id" : "NULL",
                hasKey   ? ":event_key"   : "NULL");

        var spec = db.sql(sql)
                .bind("tenant_id", tenantId)
                .bind("saga_id", sagaId)
                .bind("aggregate_type", aggregateType)
                .bind("event_type", eventType)
                .bind("payload", payload)
                .bind("headers_json", headersJson);

        if (hasAggId) spec = spec.bind("aggregate_id", aggregateId);
        if (hasKey)   spec = spec.bind("event_key", eventKey);

        return spec.map(OutboxStoreImpl::map).one();
    }

    @Override
    public Flux<OutboxRow> leaseBatchForTenant(String tenantId, int batchSize, Duration leaseDuration) {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final OffsetDateTime until = now.plus(leaseDuration);

        final String sql = """
            WITH cte AS (
              SELECT id, created_on
              FROM outbox
              WHERE tenant_id = :tenant_id
                AND (lease_until IS NULL OR lease_until < :now)
              ORDER BY created_at
              LIMIT :limit
              FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox o
               SET lease_until = :until, attempts = o.attempts + 1, updated_at = :now
              FROM cte
             WHERE o.id = cte.id AND o.created_on = cte.created_on
            RETURNING o.*
            """;

        return db.sql(sql)
                .bind("tenant_id", tenantId)
                .bind("now", now)
                .bind("until", until)
                .bind("limit", batchSize)
                .map(OutboxStoreImpl::map)
                .all();
    }

    @Override
    public Mono<Integer> markPublished(Collection<OutboxKey> keys) {
        if (keys == null || keys.isEmpty()) return Mono.just(0);
        final OffsetDateTime ts = OffsetDateTime.now(ZoneOffset.UTC);

        return Flux.fromIterable(keys)
                .flatMap(k -> db.sql("""
                        UPDATE outbox SET lease_until = NULL, updated_at = :ts
                        WHERE id = :id AND created_on = :co
                        """)
                        .bind("ts", ts)
                        .bind("id", k.id())
                        .bind("co", k.createdOn())
                        .fetch().rowsUpdated(), 32)
                .map(Number::intValue)
                .reduce(0, Integer::sum);
    }

    @Override
    public Mono<Integer> deleteByKeys(Collection<OutboxKey> keys) {
        if (keys == null || keys.isEmpty()) return Mono.just(0);

        return Flux.fromIterable(keys)
                .flatMap(k -> db.sql("""
                        DELETE FROM outbox
                        WHERE id = :id AND created_on = :co
                        """)
                        .bind("id", k.id())
                        .bind("co", k.createdOn())
                        .fetch().rowsUpdated(), 32)
                .map(Number::intValue)
                .reduce(0, Integer::sum);
    }

    private static OutboxRow map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        return new OutboxRow(
                row.get("id", Long.class),
                row.get("created_on", java.time.LocalDate.class),
                row.get("tenant_id", String.class),
                row.get("saga_id", java.util.UUID.class),
                row.get("aggregate_type", String.class),
                row.get("aggregate_id", Long.class),
                row.get("event_type", String.class),
                row.get("event_key", String.class),
                row.get("payload", String.class),
                row.get("headers_json", String.class),
                row.get("attempts", Integer.class),
                row.get("lease_until", java.time.OffsetDateTime.class),
                row.get("created_at", java.time.OffsetDateTime.class),
                row.get("updated_at", java.time.OffsetDateTime.class)
        );
    }
}
