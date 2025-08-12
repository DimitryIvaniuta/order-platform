package com.github.dimitryivaniuta.gateway.auth;

import com.github.dimitryivaniuta.gateway.dto.MintedToken;
import com.github.dimitryivaniuta.gateway.security.JwtKeyManager;
import com.github.dimitryivaniuta.gateway.security.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Issues RS256 signed JWT access tokens using the active RSA key.
 *
 * Claims included:
 * - iss, aud, sub, iat, nbf, exp, jti
 * - preferred_username, email (optional)
 * - scope (space-delimited)
 * - tenant_id
 * - mt (multi-tenant roles map)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtIssuerServiceImpl implements JwtIssuerService {

    /** JWT settings such as issuer, audience and access token TTL. */
    private final JwtProperties jwtProperties;

    /** Supplies the currently active RSA signing key and key id (kid). */
    private final JwtKeyManager keyManager;

    @Override
    public reactor.core.publisher.Mono<MintedToken> mintAccessToken(
            final String subject,
            final String tenantId,
            final Map<String, List<String>> mt,
            final List<String> scopes,
            final String username,
            final String email
    ) {
        return reactor.core.publisher.Mono.fromCallable(() -> {
            // Resolve signing key and header with kid
            RSAKey rsa = keyManager.getActive();
            RSASSASigner signer = new RSASSASigner(rsa.toPrivateKey());
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(rsa.getKeyID())
                    .build();

            // Timestamps
            Instant now = Instant.now();
            Duration ttl = jwtProperties.getAccessTokenTtl();
            if (ttl == null || ttl.isNegative() || ttl.isZero()) {
                ttl = Duration.ofMinutes(10);
            }
            Instant exp = now.plus(ttl);

            // Standard and custom claims
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(jwtProperties.getIssuer())
                    .audience(jwtProperties.getAudience())
                    .subject(subject)
                    .issueTime(Date.from(now))
                    .notBeforeTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("preferred_username", username)
                    .claim("tenant_id", tenantId)
                    .claim("mt", mt)
                    .claim("scope", String.join(" ", scopes));

            if (email != null && !email.isBlank()) {
                claims.claim("email", email);
            }

            SignedJWT jwt = new SignedJWT(header, claims.build());
            jwt.sign(signer);

            String token = jwt.serialize();
            if (log.isDebugEnabled()) {
                log.debug("Minted JWT sub={} tenant={} aud={} exp={}", subject, tenantId, jwtProperties.getAudience(), exp);
            }
            return new MintedToken(token, ttl);
        });
    }
}
