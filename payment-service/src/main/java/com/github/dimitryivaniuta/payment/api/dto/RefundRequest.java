package com.github.dimitryivaniuta.payment.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** Request to refund a payment (partial or full). */
@Jacksonized
@Builder
public record RefundRequest(
        @NotNull @Positive Long amountMinor,
        @NotNull @Size(min = 3, max = 3) String currencyCode,
        String reasonCode
) {}
