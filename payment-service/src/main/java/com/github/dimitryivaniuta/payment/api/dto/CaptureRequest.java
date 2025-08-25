package com.github.dimitryivaniuta.payment.api.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/**
 * Request to capture a payment.
 * If amountMinor is null, capture the remaining authorized amount.
 */
@Jacksonized
@Builder
public record CaptureRequest(
        @Positive Long amountMinor,
        @Size(min = 3, max = 3) String currencyCode
) {}
