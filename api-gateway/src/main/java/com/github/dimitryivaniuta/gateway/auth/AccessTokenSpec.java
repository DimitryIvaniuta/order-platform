package com.github.dimitryivaniuta.gateway.auth;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable token specification.
 * Keep all fields minimal and serializable-friendly; extend as needed.
 */
@Getter
@Builder
public class AccessTokenSpec {
    /** Optional audience override; defaults to {@code security.jwt.audience}. */
    String audienceOverride;

    /** Extra custom claims to merge into the token. */
    Map<String, Object> additionalClaims;

    /** Multi-tenant roles claim (tenant -> role list), mapped into "mt". */
    Map<String, List<String>> mt;

    /** JWT ID to set as {@code jti}; if null, a random UUID is used. */
    String jti;

    /** Granted scopes; will be joined with spaces into the "scope" claim. */
    List<String> scopes;

    /** Tenant id included as {@code tenant_id}. */
    String tenantId;

    /** Token TTL override; defaults to {@code security.jwt.access-token-ttl}. */
    Duration ttlOverride;

    /** Subject (user id) written into {@code sub}. */
    String userId;

    /** Preferred username for {@code preferred_username}. */
    String username;

    /**
     * Convenience builder for the most common case.
     *
     * @param userId subject/user id
     * @param username preferred username
     * @param tenantId tenant id (nullable)
     * @param scopes scopes list (nullable)
     * @return spec instance without overrides
     */
    public static AccessTokenSpec of(final String userId, final String username, final String tenantId, final List<String> scopes) {
        return AccessTokenSpec.builder()
                .audienceOverride(null)
                .additionalClaims(null)
                .mt(null)
                .scopes(scopes)
                .tenantId(tenantId)
                .ttlOverride(null)
                .userId(userId)
                .username(username)
                .build();
    }
}