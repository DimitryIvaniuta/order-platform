package com.github.dimitryivaniuta.gateway.web.dto;


import java.util.Map;

/**
 * OAuth2-style token response.
 */
public record TokenResponse(
        /** The signed RS256 JWT. */
        String access_token,
        /** Always "Bearer". */
        String token_type,
        /** Expiration (seconds). */
        long expires_in,
        /** Additional convenience properties (e.g., scope). */
        Map<String, Object> ext
) { }