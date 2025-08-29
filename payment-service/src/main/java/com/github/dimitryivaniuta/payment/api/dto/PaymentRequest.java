package com.github.dimitryivaniuta.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;

@Jacksonized
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentRequest(
        @NotNull
        @JsonProperty("amount")
        Amount amount,

        /** Your merchant reference (idempotent in your domain). */
        @NotBlank
        @JsonProperty("reference")
        String reference,

        /** Adyen merchant account. */
        @NotBlank
        @JsonProperty("merchantAccount")
        String merchantAccount,

        /** Tokenized payment method map per Adyen spec. */
        @NotNull
        @JsonProperty("paymentMethod")
        Map<String, Object> paymentMethod,

        /** Browser/app return URL (for 3DS / redirect). */
        @JsonProperty("returnUrl")
        String returnUrl,

        /** Persist method at Adyen if supported. */
        @JsonProperty("storePaymentMethod")
        Boolean storePaymentMethod,

        /** Extra hints like { "allow3DS2": true }. */
        @JsonProperty("additionalData")
        Map<String, Object> additionalData
) {
    @Jacksonized
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static record Amount(
            @NotBlank
            @JsonProperty("currency")
            String currency,

            @NotNull
            @PositiveOrZero
            @JsonProperty("value")
            Long value
    ) {}
}
