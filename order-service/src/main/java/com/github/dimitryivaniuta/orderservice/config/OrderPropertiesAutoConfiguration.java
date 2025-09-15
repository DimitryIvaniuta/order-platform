package com.github.dimitryivaniuta.orderservice.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Registers config properties for the gateway.
 */
@AutoConfiguration
@EnableConfigurationProperties({
        JwtProps.class
})
public class OrderPropertiesAutoConfiguration {
    // no beans needed; we just register properties
}
