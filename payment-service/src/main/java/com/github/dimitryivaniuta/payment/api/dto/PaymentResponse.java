package com.github.dimitryivaniuta.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payment projection returned by the API.
 */
@Jacksonized
@Builder
@JsonInclude(Include.NON_NULL)
public record PaymentResponse(
        @Schema(description = "Payment id")                  UUID id,
        @Schema(description = "Tenant id")                   String tenantId,
        @Schema(description = "Order id")                    Long orderId,
        @Schema(description = "User id")                     UUID userId,

        @Schema(description = "ISO 4217 currency")           String currency,

        @Schema(description = "Total authorized amount")     BigDecimal authorizedAmount,
        @Schema(description = "Total captured amount")       BigDecimal capturedAmount,
        @Schema(description = "Total refunded amount")       BigDecimal refundedAmount,
        @Schema(description = "Remaining capturable amount") BigDecimal remainingAuthorized,

        @Schema(description = "Payment status", example = "AUTHORIZED")
        String status,

        @Schema(description = "Payment method type", example = "CARD_TOKEN")
        String paymentMethod,

        @Schema(description = "Correlation id for tracing")
        String correlationId,

        @Schema(description = "Created timestamp (UTC)")
        Instant createdAt,

        @Schema(description = "Updated timestamp (UTC)")
        Instant updatedAt
) { }
