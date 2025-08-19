package com.github.dimitryivaniuta.gateway.service;


import com.github.dimitryivaniuta.gateway.web.dto.MintedToken;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Service responsible for minting signed RS256 JWTs from user/session context.
 *
 * <p>This is the only dependency that actually creates the token; it should use
 * your {@code JwtKeyManager} and {@code JwtProperties} internally. Keeping a
 * stable interface here avoids controller changes if internals evolve.</p>
 */
public interface JwtIssuerService {
    /**
     * Mints an access token with standard OIDC + custom claims:
     * <ul>
     *   <li>sub, iss, aud, iat, exp, nbf</li>
     *   <li>preferred_username, email (optional)</li>
     *   <li>scope (space-delimited)</li>
     *   <li>tenant_id</li>
     *   <li>mt (multi-tenant roles map)</li>
     * </ul>
     *
     * @param subject      user id as string
     * @param tenantId     current tenant id
     * @param mt           multi-tenant roles (tenant -> roles[])
     * @param scopes       scopes to include (becomes space-delimited "scope" claim)
     * @param username     preferred_username
     * @param email        email (nullable)
     * @return a reactive wrapper with the compact token and its ttl
     */
    Mono<MintedToken> mintAccessToken(
            String subject,
            String tenantId,
            Map<String, List<String>> mt,
            List<String> scopes,
            String username,
            String email
    );
}