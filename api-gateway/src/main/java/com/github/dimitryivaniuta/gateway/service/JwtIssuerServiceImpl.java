package com.github.dimitryivaniuta.gateway.service;

import com.github.dimitryivaniuta.gateway.web.dto.MintedToken;
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
import java.util.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Issues RS256 signed JWT access tokens using the active RSA key.
 * Claims: iss, aud, sub, iat, nbf, exp, jti, preferred_username, email, scope, tenant_id, mt.
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
    public Mono<MintedToken> mintAccessToken(
            final String subject,
            final String tenantId,
            final Map<String, List<String>> mt,
            final List<String> scopes,
            final String username,
            final String email
    ) {
        return Mono.fromCallable(() -> {
            // Get current signing key from your manager
            RSAKey rsa = keyManager.currentSigningKey();
            if (rsa == null || rsa.toPrivateKey() == null) {
                throw new IllegalStateException("No active signing key available");
            }

            var signer = new RSASSASigner(rsa.toPrivateKey());
            var header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(rsa.getKeyID())
                    .build();

            var now = Instant.now();
            Duration ttl = jwtProperties.getAccessTokenTtl();
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                ttl = Duration.ofMinutes(10);
            }
            var exp = now.plus(ttl);

            List<String> aud = Arrays.stream(jwtProperties.getAudience().split("[,\\s]+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            var claims = new JWTClaimsSet.Builder()
                    .issuer(jwtProperties.getIssuer())
                    .audience(aud)      // supports String vararg
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

            var jwt = new SignedJWT(header, claims.build());
            jwt.sign(signer);

            var token = jwt.serialize();
            if (log.isDebugEnabled()) {
                log.debug("Minted JWT sub={} tenant={} aud={} kid={} exp={}",
                        subject, tenantId, jwtProperties.getAudience(), rsa.getKeyID(), exp);
            }
            return new MintedToken(token, ttl);
        });
    }
}
