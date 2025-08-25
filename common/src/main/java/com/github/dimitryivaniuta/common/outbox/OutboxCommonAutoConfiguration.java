package com.github.dimitryivaniuta.common.outbox;

import com.github.dimitryivaniuta.common.kafka.AppKafkaProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@EnableConfigurationProperties({
        OutboxPublisherProperties.class
})
public class OutboxCommonAutoConfiguration {
}
