package com.github.dimitryivaniuta.gateway.security;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal OIDC/OAuth2 discovery so issuer-uri works in local/dev.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OidcDiscoveryController {

    /** Base issuer URL (must equal the 'iss' claim in tokens). */
    @Value("${security.jwt.issuer}")
    private String issuer;

    /**
     * OpenID Provider Configuration (RFC 8414 / OIDC discovery).
     * Spring only needs 'issuer' and 'jwks_uri' for resource-server use.
     */
    @GetMapping(value = "/.well-known/openid-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> openidConfiguration() {
        String jwks = issuer.endsWith("/") ? issuer + ".well-known/jwks.json"
                : issuer + "/.well-known/jwks.json";
        return Map.of(
                "issuer", issuer,
                "jwks_uri", jwks,
                // a few common fields so tools don’t complain (placeholders in local)
                "subject_types_supported", new String[] { "public" },
                "id_token_signing_alg_values_supported", new String[] { "RS256" }
        );
    }
}
