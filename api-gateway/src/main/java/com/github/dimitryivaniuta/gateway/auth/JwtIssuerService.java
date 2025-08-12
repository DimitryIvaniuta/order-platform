package com.github.dimitryivaniuta.gateway.auth;

import com.github.dimitryivaniuta.gateway.security.JwtKeyManager;
import com.github.dimitryivaniuta.gateway.security.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Issues RS256 JWT access tokens using the current signing key from {@link JwtKeyManager}.
 * <p>
 * - Uses {@link JwtProperties} for issuer, audience, and TTL.<br>
 * - Adds a {@code kid} header, and standard claims: {@code iss, aud, sub, iat, nbf, exp, jti}.<br>
 * - Supports multi-tenant and scope claims: {@code tenant_id}, space-delimited {@code scope}, and optional {@code mt} map.<br>
 * - Reactive API returning {@link Mono} to fit WebFlux pipelines.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtIssuerService {

    /** Key manager that supplies the current RSA signing key and exposes JWKS. */
    private final JwtKeyManager jwtKeyManager;

    /** Strongly-typed JWT settings: issuer, audience, TTL, rotation/retention. */
    private final JwtProperties jwtProperties;

    /**
     * Issues a compact RS256 JWT string for the given specification.
     *
     * @param spec token specification (subject, username, scopes, tenant, optional ttl override, etc.)
     * @return mono with compact serialized JWT (JWS)
     */
    public Mono<String> issueAccessToken(@NonNull final AccessTokenSpec spec) {
        return Mono.fromCallable(() -> {
            // Resolve temporal values
            final Instant now = Instant.now();
            final Duration ttl = spec.getTtlOverride() != null ? spec.getTtlOverride()
                    : Objects.requireNonNullElse(jwtProperties.getAccessTokenTtl(), Duration.ofMinutes(10));
            final Instant exp = now.plus(ttl);

            // Resolve issuer & audience (fail-fast if missing)
            final String issuer = Objects.requireNonNull(jwtProperties.getIssuer(), "security.jwt.issuer must be set");
            final String audience = spec.getAudienceOverride() != null ? spec.getAudienceOverride() : jwtProperties.getAudience();
            Objects.requireNonNull(audience, "security.jwt.audience must be set");

            // Get current signing key
            final RSAKey rsa = jwtKeyManager.currentSigningKey();
            final PrivateKey privateKey = rsa.toPrivateKey();

            // Build header with kid + RS256
            final JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(rsa.getKeyID())
                    .build();

            // Standard claims
            final var builder = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(List.of(audience))
                    .subject(spec.getUserId())
                    .issueTime(Date.from(now))
                    .notBeforeTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .jwtID(spec.getJti() != null ? spec.getJti() : UUID.randomUUID().toString())
                    // Common app claims
                    .claim("preferred_username", spec.getUsername())
                    .claim("tenant_id", spec.getTenantId() == null ? "" : spec.getTenantId())
                    .claim("scope", String.join(" ", spec.getScopes() == null ? List.of() : spec.getScopes()));

            // Optional multi-tenant role map (e.g., { "acme": ["ORDER_READ","ORDER_WRITE"] })
            if (spec.getMt() != null && !spec.getMt().isEmpty()) {
                builder.claim("mt", spec.getMt());
            }

            // Merge any additional custom claims
            if (spec.getAdditionalClaims() != null && !spec.getAdditionalClaims().isEmpty()) {
                spec.getAdditionalClaims().forEach(builder::claim);
            }

            // Sign
            final SignedJWT jwt = new SignedJWT(header, builder.build());
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        });
    }

}
