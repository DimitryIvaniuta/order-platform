package com.github.dimitryivaniuta.orderservice.config;

import com.github.dimitryivaniuta.common.kafka.AppKafkaProperties;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class KafkaReactorConfig {

    private final AppKafkaProperties props;

    @Bean
    KafkaSender<String, byte[]> kafkaSender() {
        Map<String, Object> p = new HashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        p.put(ProducerConfig.CLIENT_ID_CONFIG, props.getClientId());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return KafkaSender.create(SenderOptions.create(p));
    }

    @Bean
    KafkaReceiver<String, byte[]> kafkaReceiver() {
        Map<String, Object> c = new HashMap<>();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        c.put(ConsumerConfig.GROUP_ID_CONFIG, props.getGroupId());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        // Example: subscribe only to ORDER events here (gateway may subscribe to all)
        ReceiverOptions<String, byte[]> ro = ReceiverOptions.<String, byte[]>create(c)
                .subscription(List.of(props.getTopics().getEvents().getOrder()));

        return KafkaReceiver.create(ro);
    }
}
