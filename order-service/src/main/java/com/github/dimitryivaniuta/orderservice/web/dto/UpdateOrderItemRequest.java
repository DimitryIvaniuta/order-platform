package com.github.dimitryivaniuta.orderservice.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record UpdateOrderItemRequest(
        @Positive Integer quantity,
        @DecimalMin(value = "0.00") BigDecimal unitPrice,
        String name
) {}