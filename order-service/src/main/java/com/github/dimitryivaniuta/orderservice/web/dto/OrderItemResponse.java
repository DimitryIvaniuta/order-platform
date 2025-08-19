package com.github.dimitryivaniuta.orderservice.web.dto;

import com.github.dimitryivaniuta.orderservice.model.OrderItemEntity;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
        Long id,
        UUID productId,
        String sku,
        String name,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
    public static OrderItemResponse from(OrderItemEntity e) {
        return new OrderItemResponse(
                e.getId(), e.getProductId(), e.getSku(), e.getName(),
                e.getQuantity(), e.getUnitPrice(), e.getLineTotal()
        );
    }
}
