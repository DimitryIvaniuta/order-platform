package com.github.dimitryivaniuta.payment.provider.adyen.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Jacksonized
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentCaptureRequest(
        @NotNull
        @JsonProperty("amount")
        Amount amount,

        @NotBlank
        @JsonProperty("merchantAccount")
        String merchantAccount,

        @NotBlank
        @JsonProperty("reference")
        String reference
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