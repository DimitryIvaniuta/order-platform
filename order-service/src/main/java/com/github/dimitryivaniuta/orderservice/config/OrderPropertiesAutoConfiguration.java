package com.github.dimitryivaniuta.orderservice.config;

import com.github.dimitryivaniuta.common.outbox.OutboxPublisherProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Registers config properties for the gateway.
 */
@AutoConfiguration
@EnableConfigurationProperties({
        OutboxPublisherProperties.class,
        JwtProps.class
})
public class OrderPropertiesAutoConfiguration {
    // no beans needed; we just register properties
}
