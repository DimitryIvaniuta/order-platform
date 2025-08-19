package com.github.dimitryivaniuta.orderservice.web.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Row view mapped from SELECT/RETURNING. */
public record OutboxRow(
        Long id,
        LocalDate createdOn,
        String tenantId,
        UUID sagaId,
        String aggregateType,
        Long aggregateId,
        String eventType,
        String eventKey,
        String payload,        // TEXT (JSON string)
        String headersJson,    // TEXT (JSON string)
        Integer attempts,
        OffsetDateTime leaseUntil,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
