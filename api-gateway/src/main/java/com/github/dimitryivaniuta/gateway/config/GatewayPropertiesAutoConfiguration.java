package com.github.dimitryivaniuta.gateway.config;

import com.github.dimitryivaniuta.common.kafka.AppKafkaProperties;
import com.github.dimitryivaniuta.common.security.MultiTenantAuthzProperties;
import com.github.dimitryivaniuta.gateway.security.JwtProperties;
import com.github.dimitryivaniuta.gateway.security.ResourceServerJwtProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Registers config properties for the gateway.
 */
@AutoConfiguration
@EnableConfigurationProperties({
        JwtProperties.class,
        ResourceServerJwtProperties.class,
        MultiTenantAuthzProperties.class,
        AppKafkaProperties.class,
})
public class GatewayPropertiesAutoConfiguration {
    // no beans needed; we just register properties
}