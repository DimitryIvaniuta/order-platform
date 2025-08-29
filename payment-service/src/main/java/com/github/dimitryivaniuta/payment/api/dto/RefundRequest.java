package com.github.dimitryivaniuta.payment.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

@Jacksonized
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundRequest(
        /** Your Adyen merchant account. */
        @NotBlank @JsonProperty("merchantAccount") String merchantAccount,
        /** Your idempotent merchant reference for this refund. */
        @NotBlank @JsonProperty("reference") String reference,
        /** Refund amount in minor units + ISO currency. */
        @NotNull  @JsonProperty("amount") Amount amount
        // List<Split> splits,
        // List<LineItem> lineItems
) {
    @Jacksonized
    @Builder
    public record Amount(
            /** ISO 4217 (e.g., "USD"). */
            @NotBlank @JsonProperty("currency") String currency,
            /** Minor units (e.g., cents) per Adyen spec. */
            @NotNull @PositiveOrZero @JsonProperty("value") Long value
    ) {}
}
