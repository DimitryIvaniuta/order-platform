package com.github.dimitryivaniuta.gateway.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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

    /** Typed Kafka properties (alphabetically ordered private fields). */
    private final GatewayKafkaProperties props;

    /**
     * Producer options (string key, byte[] value) with compression and strong durability (acks=all).
     */
    @Bean
    public SenderOptions<String, byte[]> senderOptions() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        cfg.put(ProducerConfig.CLIENT_ID_CONFIG, props.getClientId());
        cfg.put(ProducerConfig.ACKS_CONFIG, props.getAcks());
        cfg.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, props.getCompressionType());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);

        // Reasonable batching defaults; adjust if needed.
        cfg.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        cfg.put(ProducerConfig.BATCH_SIZE_CONFIG, 32 * 1024);     // 32KB
        cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

        return SenderOptions.<String, byte[]>create(cfg)
                .maxInFlight(1024)
                .scheduler(Schedulers.boundedElastic());
    }

    /**
     * Base consumer options (manual commit, earliest reset) for string key / byte[] value.
     * Reactor-Kafka will drive commit intervals downstream.
     */
    @Bean
    public ReceiverOptions<String, byte[]> receiverOptions() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, props.getGroupId());
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Throughput-friendly polling; adjust for your latency SLOs.
        cfg.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        cfg.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 250);

        return ReceiverOptions.<String, byte[]>create(cfg)
                .commitInterval(Duration.ofSeconds(2));
    }

    /**
     * A {@link KafkaReceiver} subscribed to the configured event topics, used by the Saga consumer.
     * If no topics are configured, the bean still initializes but with an empty subscription.
     */
    @Bean
    public KafkaReceiver<String, byte[]> kafkaReceiver(final ReceiverOptions<String, byte[]> base) {
        ReceiverOptions<String, byte[]> ro =
                (props.getEventTopics() == null || props.getEventTopics().isEmpty())
                        ? base
                        : base.subscription(props.getEventTopics());

        log.info("KafkaReceiver initialized groupId={} topics={}", props.getGroupId(), props.getEventTopics());
        return KafkaReceiver.create(ro);
    }
}
