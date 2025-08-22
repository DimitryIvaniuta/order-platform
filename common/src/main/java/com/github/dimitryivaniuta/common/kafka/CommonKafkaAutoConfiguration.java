package com.github.dimitryivaniuta.common.kafka;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Auto-config that only exposes strongly-typed Kafka topic names via KafkaTopicsProperties.
 * No Kafka beans are created here, so we intentionally avoid @ConditionalOnClass.
 */
@AutoConfiguration
@EnableConfigurationProperties({
        AppKafkaProperties.class
})
public class CommonKafkaAutoConfiguration {
}