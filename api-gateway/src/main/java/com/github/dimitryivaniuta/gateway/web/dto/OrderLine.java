package com.github.dimitryivaniuta.gateway.web.dto;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Single order line. Record components are listed in alphabetical order.
 */
public record OrderLine(
        /** Quantity (positive integer). */
        int quantity,
        /** Stock keeping unit. */
        String sku,
        /** Unit price (non-negative). */
        BigDecimal unitPrice
) {
    public OrderLine {
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must be >= 0");
        }
    }
}