package com.github.dimitryivaniuta.gateway.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;

import java.util.List;

/**
 * Request payload for creating an order via the gateway.
 * <p>Validated with Jakarta Bean Validation. The {@code lines} must be non-empty.</p>
 */
@Getter
public final class CreateOrderRequest {
    /** Foreign key of the customer (validated at order-service side as well). */
    @NotNull
    private Long customerId;

    /** Line items (each item corresponds to {@link OrderLine}). Must be non-empty. */
    @Valid
    @NotEmpty
    private List<OrderLine> lines;
}