package com.github.dimitryivaniuta.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Request to authorize a payment (create payment intent).
 */
@Jacksonized
@Builder
public record PaymentAuthorizeRequest(
        @NotNull
        @Schema(description = "Order id to pay for", example = "123")
        Long orderId,

        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 19, fraction = 2)
        @Schema(description = "Amount to authorize (major units)", example = "149.99")
        BigDecimal amount,

        @NotBlank @Pattern(regexp = "^[A-Z]{3}$")
        @Schema(description = "ISO 4217 currency code", example = "USD")
        String currency,

        @NotBlank @Size(max = 40)
        @Schema(description = "Payment method type (e.g. CARD_TOKEN, WALLET, CARD_ON_FILE)", example = "CARD_TOKEN")
        String paymentMethod,

        @Size(max = 256)
        @Schema(description = "Opaque token / instrument id for the chosen payment method", example = "tok_1PEYk7...")
        String paymentMethodToken,

        @Schema(description = "Additional PSP metadata / hints")
        @JsonInclude(Include.NON_EMPTY)
        Map<String, Object> paymentMeta
) {
}
