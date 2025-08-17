package com.github.dimitryivaniuta.orderservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProps {
    /** e.g. http://localhost:8080/api (if you rely on discovery) */
    private String issuer;

    /** e.g. http://localhost:8080/api/.well-known/jwks.json (if you skip discovery) */
    private String jwksUri;

    /** optional: comma/space separated audiences: "order-service,api" */
    private String audience;
}