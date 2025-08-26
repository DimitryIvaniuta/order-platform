package com.github.dimitryivaniuta.payment.outbox;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Materialized view of an outbox row returned by SQL. */
public record OutboxRow(
        Long id,
        LocalDate createdOn,
        String tenantId,
        UUID sagaId,
        String aggregateType,
        Long aggregateId,
        String eventType,
        String eventKey,
        String payload,
        String headersJson,
        Integer attempts,
        OffsetDateTime leaseUntil,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
