package com.github.dimitryivaniuta.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@lombok.Builder
@lombok.extern.jackson.Jacksonized
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PaymentCaptureRequest(
        @jakarta.validation.constraints.DecimalMin("0.01")
        @jakarta.validation.constraints.Digits(integer = 19, fraction = 2)
        java.math.BigDecimal amount,
        @jakarta.validation.constraints.Size(max = 64)
        String idempotencyKey
) {}