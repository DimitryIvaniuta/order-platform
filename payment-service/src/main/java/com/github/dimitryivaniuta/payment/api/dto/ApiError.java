package com.github.dimitryivaniuta.payment.api.dto;

import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** Standardized API error payload. */
@Jacksonized
@Builder
public record ApiError(
        String code,
        String message,
        Instant timestamp,
        Map<String, Object> details
) {
    public static ApiError of(String code, String message) {
        return ApiError.builder().code(code).message(message).timestamp(Instant.now()).build();
    }
}
