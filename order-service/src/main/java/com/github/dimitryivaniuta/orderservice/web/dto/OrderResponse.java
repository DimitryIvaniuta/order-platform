package com.github.dimitryivaniuta.orderservice.web.dto;

import com.github.dimitryivaniuta.orderservice.model.OrderEntity;
import com.github.dimitryivaniuta.orderservice.model.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        Long id,
        String tenantId,
        UUID userId,
        BigDecimal totalAmount,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static OrderResponse fromEntity(OrderEntity e) {
        return new OrderResponse(
                e.getId(), e.getTenantId(), e.getUserId(),
                e.getTotalAmount(), e.getStatus(), e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}