package com.github.dimitryivaniuta.orderservice.web.dto;

import com.github.dimitryivaniuta.orderservice.model.OrderEntity;
import com.github.dimitryivaniuta.orderservice.model.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        Long id,
        String tenantId,
        UUID userId,
        BigDecimal totalAmount,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt,
        List<OrderItemResponse> items
) {
    public static OrderResponse fromEntity(OrderEntity e) {
        return new OrderResponse(
                e.getId(), e.getTenantId(), e.getUserId(),
                e.getTotalAmount(), e.getStatus(), e.getCreatedAt(), e.getUpdatedAt(), null
        );
    }

    public OrderResponse withItems(List<OrderItemResponse> items) {
        return new OrderResponse(id, tenantId, userId, totalAmount, status, createdAt, updatedAt, items);
    }

}