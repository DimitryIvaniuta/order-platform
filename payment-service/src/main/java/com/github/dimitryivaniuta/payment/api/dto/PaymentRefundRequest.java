package com.github.dimitryivaniuta.payment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

/**
 * Request to refund a captured payment (full or partial).
 */
@Jacksonized
@Builder
public record PaymentRefundRequest(
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 19, fraction = 2)
        @Schema(description = "Amount to refund (major units)", example = "49.99")
        BigDecimal amount,

        @Size(max = 256)
        @Schema(description = "Human-readable reason", example = "Customer returned item")
        String reason
) { }
