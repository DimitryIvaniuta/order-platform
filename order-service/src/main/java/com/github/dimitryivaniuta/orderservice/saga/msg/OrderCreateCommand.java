package com.github.dimitryivaniuta.orderservice.saga.msg;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderCreateCommand(
        UUID sagaId,
        String tenantId,
        Long orderId,
        UUID userId,
        BigDecimal totalAmount,
        Instant ts
) {
}
