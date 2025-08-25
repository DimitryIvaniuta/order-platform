package com.github.dimitryivaniuta.payment.api.dto;

import java.util.Map;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** SCA/3DS next-action payload (redirect URL, SDK params, etc.). */
@Jacksonized
@Builder
public record NextActionDto(
        String type,                 // e.g., "REDIRECT", "THREEDS_CHALLENGE"
        Map<String, Object> data     // provider-specific map (safe to expose)
) {}
