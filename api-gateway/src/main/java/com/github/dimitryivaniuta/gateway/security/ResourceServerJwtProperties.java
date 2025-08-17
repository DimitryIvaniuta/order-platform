package com.github.dimitryivaniuta.gateway.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "spring.security.oauth2.resourceserver.jwt")
public class ResourceServerJwtProperties {

    private String issuerUri;

    private String jwkSetUri;

    public boolean hasIssuer() {
        return issuerUri != null && !issuerUri.isBlank();
    }

    public boolean hasJwks() {
        return jwkSetUri != null && !jwkSetUri.isBlank();
    }

}
