package com.github.dimitryivaniuta.gateway.security;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Custom JWT issuing + rotation settings for the gateway.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    /** Expected audience for this API. */
    private String audience;

    /** Access token lifetime. */
    private Duration accessTokenTtl = Duration.ofMinutes(10);

    /** Issuer value to stamp into tokens and expose via discovery. */
    private String issuer;

    /** Rotation frequency for the signing key. */
    private Duration keyRotationInterval = Duration.ofHours(24);

    /** How long to retain old keys to validate older tokens. */
    private Duration keyRetention = Duration.ofHours(48);

    private String jwksUri;

    public boolean hasJwks() {
        return jwksUri != null && !jwksUri.isBlank();
    }

}
