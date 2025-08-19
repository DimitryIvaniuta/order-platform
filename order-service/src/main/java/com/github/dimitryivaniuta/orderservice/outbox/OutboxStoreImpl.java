package com.github.dimitryivaniuta.orderservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxKey;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxRow;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

@Repository("outboxRepository") // keeps old bean name if you referenced it by name
@RequiredArgsConstructor
public class OutboxStoreImpl implements OutboxStore {

    private final DatabaseClient db;
    private final ObjectMapper om;

    // -------------------- INSERT --------------------

    @Override
    public Mono<OutboxRow> insertRow(
            String tenantId,
            UUID sagaId,
            String aggregateType,
            Long aggregateId,
            String eventType,
            String eventKey,
            String payloadJson,
            Map<String, String> headers
    ) {
        requireNonNull(tenantId, "tenantId");
        requireNonNull(aggregateType, "aggregateType");
        requireNonNull(eventType, "eventType");
        requireNonNull(payloadJson, "payloadJson");

        if (sagaId == null) {
            sagaId = deriveSagaId(tenantId, aggregateType, eventKey);
        }
        String headersJson = toJson(headers == null ? Map.of() : headers);

        final String sql = """
            INSERT INTO outbox
              (tenant_id, saga_id, aggregate_type, aggregate_id, event_type, event_key,
               payload, headers_json, attempts, lease_until)
            VALUES
              (:tenant_id, :saga_id, :aggregate_type, :aggregate_id, :event_type, :event_key,
               :payload, :headers_json, 0, NULL)
            RETURNING id,
                      created_on,
                      tenant_id,
                      saga_id,
                      aggregate_type,
                      aggregate_id,
                      event_type,
                      event_key,
                      payload,
                      headers_json,
                      attempts,
                      lease_until,
                      created_at,
                      updated_at
            """;

        return db.sql(sql)
                .bind("tenant_id", tenantId)
                .bind("saga_id", sagaId)
                .bind("aggregate_type", aggregateType)
                .bind("aggregate_id", aggregateId)
                .bind("event_type", eventType)
                .bind("event_key", eventKey)
                .bind("payload", payloadJson)
                .bind("headers_json", headersJson)
                .map(OutboxStoreImpl::mapRow)
                .one();
    }

    // -------------------- CLAIM BATCH --------------------

    @Override
    public Flux<OutboxRow> claimBatch(String tenantId, int batchSize, Duration leaseDuration, Instant now) {
        requireNonNull(tenantId, "tenantId");
        requireNonNull(leaseDuration, "leaseDuration");
        requireNonNull(now, "now");

        final OffsetDateTime leaseUntil = OffsetDateTime.ofInstant(now.plus(leaseDuration), ZoneOffset.UTC);
        final OffsetDateTime nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        final String sql = """
            WITH cte AS (
              SELECT id, created_on
                FROM outbox
               WHERE tenant_id = :tenant_id
                 AND (lease_until IS NULL OR lease_until < :now_ts)
               ORDER BY created_at ASC, id ASC
               LIMIT :limit
            )
            UPDATE outbox o
               SET lease_until = :lease_until,
                   updated_at  = :now_ts
              FROM cte
             WHERE o.id = cte.id
               AND o.created_on = cte.created_on
            RETURNING o.id,
                      o.created_on,
                      o.tenant_id,
                      o.saga_id,
                      o.aggregate_type,
                      o.aggregate_id,
                      o.event_type,
                      o.event_key,
                      o.payload,
                      o.headers_json,
                      o.attempts,
                      o.lease_until,
                      o.created_at,
                      o.updated_at
            """;

        return db.sql(sql)
                .bind("tenant_id", tenantId)
                .bind("now_ts", nowTs)
                .bind("limit", batchSize)
                .bind("lease_until", leaseUntil)
                .map(OutboxStoreImpl::mapRow)
                .all();
    }

    // -------------------- RETRY / BACKOFF --------------------

    @Override
    public Mono<Long> rescheduleForRetry(OutboxKey key, Instant nextTry) {
        requireNonNull(key, "key");
        requireNonNull(nextTry, "nextTry");
        final OffsetDateTime ts = OffsetDateTime.ofInstant(nextTry, ZoneOffset.UTC);

        final String sql = """
            UPDATE outbox
               SET attempts   = attempts + 1,
                   lease_until = :ts,
                   updated_at  = :ts
             WHERE id = :id
               AND created_on = :co
            """;

        return db.sql(sql)
                .bind("ts", ts)
                .bind("id", key.id())
                .bind("co", key.createdOn())
                .fetch().rowsUpdated();
    }

    @Override
    public Mono<Long> rescheduleForRetry(Collection<OutboxKey> keys, Instant nextTry) {
        if (keys == null || keys.isEmpty()) return Mono.just(0L);
        final OffsetDateTime ts = OffsetDateTime.ofInstant(nextTry, ZoneOffset.UTC);

        final String values = buildValuesPairs(keys.size()); // "( :id0, :co0 ), ( :id1, :co1 ) ..."
        final String sql = """
            UPDATE outbox o
               SET attempts   = o.attempts + 1,
                   lease_until = :ts,
                   updated_at  = :ts
              FROM (VALUES %s) AS v(id, created_on)
             WHERE o.id = v.id AND o.created_on = v.created_on
            """.formatted(values);

        var spec = db.sql(sql).bind("ts", ts);
        int i = 0;
        for (OutboxKey k : keys) {
            spec = spec.bind("id" + i, k.id())
                    .bind("co" + i, k.createdOn());
            i++;
        }
        return spec.fetch().rowsUpdated();
    }

    // -------------------- RELEASE EXPIRED LEASES --------------------

    @Override
    public Mono<Long> releaseExpiredLeases(String tenantId, Instant now) {
        final OffsetDateTime nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        final String sql = """
            UPDATE outbox
               SET lease_until = NULL,
                   updated_at  = :now_ts
             WHERE tenant_id = :tenant_id
               AND lease_until IS NOT NULL
               AND lease_until < :now_ts
            """;
        return db.sql(sql)
                .bind("tenant_id", tenantId)
                .bind("now_ts", nowTs)
                .fetch().rowsUpdated();
    }

    // -------------------- DELETE AFTER SUCCESS --------------------

    @Override
    public Mono<Long> deleteByKey(OutboxKey key) {
        final String sql = "DELETE FROM outbox WHERE id = :id AND created_on = :co";
        return db.sql(sql)
                .bind("id", key.id())
                .bind("co", key.createdOn())
                .fetch().rowsUpdated();
    }

    @Override
    public Mono<Long> deleteByKeys(Collection<OutboxKey> keys) {
        if (keys == null || keys.isEmpty()) return Mono.just(0L);
        final String values = buildValuesPairs(keys.size());

        final String sql = """
            DELETE FROM outbox o
                  USING (VALUES %s) AS v(id, created_on)
             WHERE o.id = v.id AND o.created_on = v.created_on
            """.formatted(values);

        var spec = db.sql(sql);
        int i = 0;
        for (OutboxKey k : keys) {
            spec = spec.bind("id" + i, k.id())
                    .bind("co" + i, k.createdOn());
            i++;
        }
        return spec.fetch().rowsUpdated();
    }

    // -------------------- helpers --------------------

    private static OutboxRow mapRow(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        return new OutboxRow(
                row.get("id", Long.class),
                row.get("created_on", LocalDate.class),
                row.get("tenant_id", String.class),
                row.get("saga_id", UUID.class),
                row.get("aggregate_type", String.class),
                row.get("aggregate_id", Long.class),
                row.get("event_type", String.class),
                row.get("event_key", String.class),
                row.get("payload", String.class),
                row.get("headers_json", String.class),
                Optional.ofNullable(row.get("attempts", Integer.class)).orElse(0),
                row.get("lease_until", OffsetDateTime.class),
                row.get("created_at", OffsetDateTime.class),
                row.get("updated_at", OffsetDateTime.class)
        );
    }

    private static UUID deriveSagaId(String tenantId, String aggregateType, String eventKey) {
        var seed = (tenantId + "|" + aggregateType + "|" + String.valueOf(eventKey))
                .getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(seed);
    }

    private String toJson(Map<String, String> headers) {
        try {
            return om.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox headers map", e);
        }
    }

    /** Builds "( :id0, :co0 ), ( :id1, :co1 ), ..." for VALUES table. */
    private static String buildValuesPairs(int size) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append("(")
                    .append(":id").append(i).append(", ")
                    .append(":co").append(i)
                    .append(")");
        }
        return sb.toString();
    }
}
