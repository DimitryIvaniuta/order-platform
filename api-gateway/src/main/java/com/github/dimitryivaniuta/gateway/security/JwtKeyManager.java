package com.github.dimitryivaniuta.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWK;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Manages the active RSA signing key and retains recent public keys in JWKS.
 * For production, store keys in KMS/HSM or a secure database.
 */
@Slf4j
@Component
public class JwtKeyManager {

    /** Properties for rotation/retention. */
    private final JwtProperties props;

    /** kid -> RSAKey (private for current, public-only for retained). */
    private final Map<String, RSAKey> keys = new ConcurrentHashMap<>();

    /** Current signing key id (alphabetical field order for checkstyle). */
    @Getter
    private volatile String currentKid;

    public JwtKeyManager(final JwtProperties props) {
        this.props = props;
        rotateNow(); // initial key on startup
    }

    /** Returns the current private RSA key. */
    public RSAKey currentSigningKey() { return keys.get(currentKid); }

    /** Returns the public JWKS (current + retained). */
    public JWKSet jwkSet() {
        // Convert each stored RSAKey (private+public) to its public-only JWK,
        // then upcast to JWK so the constructor matches List<? extends JWK>.
        var publics = keys.values().stream()
                .map(RSAKey::toPublicJWK)     // no checked exception
                .map(jwk -> (JWK) jwk)        // upcast to JWK
                .toList();
        return new JWKSet(publics);
    }

    /** Rotate the signing key on a fixed delay. */
    @Scheduled(fixedDelayString = "${security.jwt.key-rotation-interval:PT24H}")
    public void rotateScheduled() {
        rotateNow();
        pruneExpired();
    }

    private void rotateNow() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            String kid = UUID.randomUUID().toString();

            RSAKey rsa = new RSAKey.Builder((RSAPublicKey) kp.getPublic())
                    .privateKey(kp.getPrivate())
                    .keyID(kid)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();

            keys.put(kid, rsa);
            currentKid = kid;
            log.info("Rotated JWT signing key; new kid={} at {}", kid, Instant.now());
        } catch (Exception e) {
            log.error("Key rotation failed", e);
        }
    }

    private void pruneExpired() {
        // Very simple retention: keep at most N public keys within retention window.
        // You can store timestamps per kid if you want time-based pruning.
        while (keys.size() > 6) { // small cap; adjust for your retention/traffic
            String first = keys.keySet().iterator().next();
            if (!first.equals(currentKid)) {
                keys.remove(first);
                log.info("Pruned old JWKS key kid={}", first);
            } else {
                break;
            }
        }
    }
}
