package com.github.dimitryivaniuta.orderservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxKey;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static java.util.Objects.requireNonNull;

@Repository("outboxRepository")
@RequiredArgsConstructor
@Slf4j
public class OutboxStoreImpl implements OutboxStore {

    private final DatabaseClient db;
    private final ObjectMapper om;

    // ------------ INSERT --------------

    @Override
    public Mono<OutboxRow> insertRow(
            String tenantId,
            UUID sagaId,
            String aggregateType,
            Long aggregateId,         // may be null
            String eventType,
            String eventKey,           // may be null
            String payloadJson,
            Map<String, String> headers
    ) {
        requireNonNull(tenantId, "tenantId");
        requireNonNull(aggregateType, "aggregateType");
        requireNonNull(eventType, "eventType");
        requireNonNull(payloadJson, "payloadJson");

        if (sagaId == null) sagaId = deriveSagaId(tenantId, aggregateType, eventKey);
        String headersJson = toJson(headers == null ? Map.of() : headers);

        // Build VALUES list with SQL NULLs for nullable columns so we NEVER bind nulls.
        final boolean hasAggId = (aggregateId != null);
        final boolean hasEvtKey = (eventKey != null && !eventKey.isBlank());

/*        final String sql = """
            INSERT INTO outbox
              (tenant_id, saga_id, aggregate_type, aggregate_id, event_type, event_key,
               payload, headers_json, attempts, lease_until)
            VALUES
              (:tenant_id::varchar, :saga_id::uuid, :aggregate_type::varchar, :aggregate_id::bigint,
               :event_type::varchar, :event_key::varchar, :payload::text, :headers_json::text, 0, NULL)
            RETURNING id, created_on, tenant_id, saga_id, aggregate_type, aggregate_id,
                      event_type, event_key, payload, headers_json, attempts, lease_until,
                      created_at, updated_at
            """;

        DatabaseClient.GenericExecuteSpec spec = db.sql(sql)
                .bind("tenant_id", tenantId)
                .bind("saga_id", sagaId)
                .bind("aggregate_type", aggregateType);

        // These may be null. Use bindNull to avoid InParameter.
        spec = bindMaybe(spec, "aggregate_id", aggregateId, Long.class);
        spec = bindMaybe(spec, "event_key",   eventKey,   String.class);

        spec = spec.bind("event_type", eventType)
                .bind("payload", payloadJson)
                .bind("headers_json", headersJson);

        return spec.map(OutboxStoreImpl::mapRow).one();*/
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
        """.formatted(
                hasAggId ? ":aggregate_id" : "NULL",
                hasEvtKey ? ":event_key"   : "NULL"
        );

        DatabaseClient.GenericExecuteSpec spec = db.sql(sql)
                .bind("tenant_id", tenantId)
                .bind("saga_id", sagaId)
                .bind("aggregate_type", aggregateType)
                .bind("event_type", eventType)
                .bind("payload", payloadJson)
                .bind("headers_json", headersJson);

        // Only bind when present (never bindNull here)
        if (hasAggId)  spec = spec.bind("aggregate_id", aggregateId);
        if (hasEvtKey) spec = spec.bind("event_key", eventKey);

        if (log.isDebugEnabled()) {
            log.debug("Outbox insert: tenant={} sagaId={} aggType={} aggId={} evtType={} evtKey={}",
                    tenantId, sagaId, aggregateType, aggregateId, eventType, eventKey);
        }

        return spec.map(OutboxStoreImpl::mapRow).one();
    }

    private static DatabaseClient.GenericExecuteSpec bindMaybe(
            DatabaseClient.GenericExecuteSpec spec, String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    // ------------ CLAIM BATCH (lease) --------------

    @Override
    public Flux<OutboxRow> claimBatch(String tenantId, int batchSize, Duration leaseDuration, Instant now) {
        requireNonNull(tenantId, "tenantId");
        requireNonNull(leaseDuration, "leaseDuration");
        requireNonNull(now, "now");

        final OffsetDateTime nowTs     = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        final OffsetDateTime leaseTs   = OffsetDateTime.ofInstant(now.plus(leaseDuration), ZoneOffset.UTC);

        // Inline LIMIT (do not bind into LIMIT). Return leased rows.
        final String sql = ("""
            WITH cte AS (
              SELECT id, created_on
                FROM outbox
               WHERE tenant_id = :tenant_id::varchar
                 AND (lease_until IS NULL OR lease_until < :now_ts::timestamptz)
               ORDER BY created_at ASC, id ASC
               LIMIT %d
            )
            UPDATE outbox o
               SET lease_until = :lease_ts::timestamptz,
                   updated_at  = :now_ts::timestamptz
              FROM cte
             WHERE o.id = cte.id
               AND o.created_on = cte.created_on
            RETURNING o.id, o.created_on, o.tenant_id, o.saga_id, o.aggregate_type, o.aggregate_id,
                      o.event_type, o.event_key, o.payload, o.headers_json, o.attempts, o.lease_until,
                      o.created_at, o.updated_at
            """).formatted(batchSize);

        return db.sql(sql)
                .bind("tenant_id", tenantId)
                .bind("now_ts",   nowTs)
                .bind("lease_ts", leaseTs)
                .map(OutboxStoreImpl::mapRow)
                .all();
    }

    // ------------ RETRY / BACKOFF --------------

    @Override
    public Mono<Integer> rescheduleForRetry(Collection<OutboxKey> keys, Instant nextTry) {
        if (keys == null || keys.isEmpty()) return Mono.just(0);
        final OffsetDateTime ts = OffsetDateTime.ofInstant(nextTry, ZoneOffset.UTC);

        // Per-row update: simple and avoids any VALUES/array ambiguity
        return Flux.fromIterable(keys)
                .flatMap(k -> db.sql("""
                            UPDATE outbox
                               SET attempts   = attempts + 1,
                                   lease_until = :ts::timestamptz,
                                   updated_at  = :ts::timestamptz
                             WHERE id = :id::bigint AND created_on = :co::date
                        """)
                        .bind("ts", ts)
                        .bind("id", k.id())
                        .bind("co", k.createdOn())
                        .fetch().rowsUpdated(), 32)
                .map(Number::intValue)
                .reduce(0, Integer::sum);
    }

    // ------------ DELETE AFTER SUCCESS --------------

    @Override
    public Mono<Integer> deleteByKeys(Collection<OutboxKey> keys) {
        if (keys == null || keys.isEmpty()) return Mono.just(0);

        return Flux.fromIterable(keys)
                .flatMap(k -> db.sql("""
                            DELETE FROM outbox
                             WHERE id = :id::bigint AND created_on = :co::date
                        """)
                        .bind("id", k.id())
                        .bind("co", k.createdOn())
                        .fetch().rowsUpdated(), 32)
                .map(Number::intValue)
                .reduce(0, Integer::sum);
    }

    // ------------ helpers --------------

    private static OutboxRow mapRow(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata md) {
        return new OutboxRow(
                row.get("id",          Long.class),
                row.get("created_on",  java.time.LocalDate.class),
                row.get("tenant_id",   String.class),
                row.get("saga_id",     java.util.UUID.class),
                row.get("aggregate_type", String.class),
                row.get("aggregate_id",   Long.class),
                row.get("event_type",     String.class),
                row.get("event_key",      String.class),
                row.get("payload",        String.class),
                row.get("headers_json",   String.class),
                Optional.ofNullable(row.get("attempts", Integer.class)).orElse(0),
                row.get("lease_until",    java.time.OffsetDateTime.class),
                row.get("created_at",     java.time.OffsetDateTime.class),
                row.get("updated_at",     java.time.OffsetDateTime.class)
        );
    }

    private static UUID deriveSagaId(String tenantId, String aggregateType, String eventKey) {
        var seed = (tenantId + "|" + aggregateType + "|" + String.valueOf(eventKey))
                .getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(seed);
    }

    private String toJson(Map<String, String> headers) {
        try { return om.writeValueAsString(headers); }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox headers", e);
        }
    }
}
