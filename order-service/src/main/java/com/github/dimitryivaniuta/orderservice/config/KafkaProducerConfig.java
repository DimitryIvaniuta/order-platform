package com.github.dimitryivaniuta.orderservice.config;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

@Configuration
public class KafkaProducerConfig {

    @Bean
    KafkaSender<String, byte[]> kafkaSender(KafkaProperties props) {
        Map<String, Object> cfg = props.buildProducerProperties(null);
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        cfg.putIfAbsent(ProducerConfig.ACKS_CONFIG, "all");
        cfg.putIfAbsent(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        cfg.putIfAbsent(ProducerConfig.LINGER_MS_CONFIG, 10);
        cfg.putIfAbsent(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        return KafkaSender.create(SenderOptions.create(cfg));
    }

}
