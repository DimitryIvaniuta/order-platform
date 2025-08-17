package com.github.dimitryivaniuta.gateway.security;

import com.nimbusds.jose.jwk.JWKSet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/.well-known")
@RequiredArgsConstructor
public class JwksController {

    private final JwtKeyManager keyManager;

    @GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        // JwtKeyManager.jwkSet() already returns a set of PUBLIC JWKs (RSAKey::toPublicJWK)
        JWKSet set = keyManager.jwkSet();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(set.toJSONObject()); // Map<String,Object> for JSON response
    }
}
