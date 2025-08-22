package com.github.dimitryivaniuta.orderservice.web.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record AddCartItemRequest(
        UUID productId,
        String sku,
        String name,
        String color,
        Map<String,String> attributes,   // optional key->value
        BigDecimal unitPrice,
        int quantity
) {}