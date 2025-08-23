package com.github.dimitryivaniuta.gateway.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.github.dimitryivaniuta.common.kafka.AppKafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.SenderOptions;

/**
 * Reactive Kafka configuration using Reactor-Kafka.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link SenderOptions} for non-blocking producers (used by {@code SagaProducer}).</li>
 *   <li>{@link ReceiverOptions} for consumers (base options).</li>
 *   <li>{@link KafkaReceiver} subscribed to configured event topics for Saga progression.</li>
 * </ul>
 * Tuning favors throughput while keeping sensible timeouts and manual commits (handled by Reactor-Kafka).
 * </p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private final ReceiverOptions<String, byte[]> base;
    private final AppKafkaProperties props;

    @Bean
    public KafkaReceiver<String, byte[]> kafkaReceiver() {
        var topics = props.allEventTopics();
        var ro = topics.isEmpty() ? base : base.subscription(topics);
        log.info("KafkaReceiver initialized groupId={} topics={}", props.getGroupId(), topics);
        return KafkaReceiver.create(ro);
    }
}