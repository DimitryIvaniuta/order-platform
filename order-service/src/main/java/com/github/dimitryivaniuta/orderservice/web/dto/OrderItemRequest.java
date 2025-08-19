package com.github.dimitryivaniuta.orderservice.web.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemRequest(
        @NotNull UUID productId,
        @NotBlank String sku,
        @Size(max = 255) String name,
        @NotNull @Positive Integer quantity,
        @NotNull @DecimalMin(value = "0.00") BigDecimal unitPrice
) {}
