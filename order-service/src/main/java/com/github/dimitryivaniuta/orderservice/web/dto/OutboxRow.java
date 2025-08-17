package com.github.dimitryivaniuta.orderservice.web.dto;

public record OutboxRow(
    Long id,
    String tenantId,
    java.util.UUID sagaId,
    String aggregateType,
    Long aggregateId,
    String eventType,
    String eventKey,
    String payload,
    String headersJson,
    int attempts
) {
}
