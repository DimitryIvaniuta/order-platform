package com.github.dimitryivaniuta.common.kafka;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-config that only exposes strongly-typed Kafka topic names via KafkaTopicsProperties.
 * No Kafka beans are created here, so we intentionally avoid @ConditionalOnClass.
 */
@AutoConfiguration
@EnableConfigurationProperties({
        AppKafkaProperties.class
})
public class KafkaCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SenderOptions<String, byte[]> senderOptions(AppKafkaProperties p) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, p.getBootstrapServers());
        cfg.put(ProducerConfig.CLIENT_ID_CONFIG, p.getClientId());
        cfg.put(ProducerConfig.ACKS_CONFIG, p.getProducer().getAcks());
        cfg.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, p.getProducer().getCompressionType());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        // sensible batching
        cfg.putIfAbsent(ProducerConfig.LINGER_MS_CONFIG, 5);
        cfg.putIfAbsent(ProducerConfig.BATCH_SIZE_CONFIG, 32 * 1024);
        cfg.putIfAbsent(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        return SenderOptions.create(cfg);
    }

    @Bean(name = "reactorKafkaSender")
    @ConditionalOnMissingBean(name = "reactorKafkaSender")
    public KafkaSender<String, byte[]> reactorKafkaSender(SenderOptions<String, byte[]> opts) {
        return KafkaSender.create(opts);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReceiverOptions<String, byte[]> receiverOptions(AppKafkaProperties p) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, p.getBootstrapServers());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, p.getGroupId());
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return ReceiverOptions.create(cfg);
    }
}