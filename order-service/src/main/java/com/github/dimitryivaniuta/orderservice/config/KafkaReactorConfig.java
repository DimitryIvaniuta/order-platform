package com.github.dimitryivaniuta.orderservice.config;

import com.github.dimitryivaniuta.common.kafka.AppKafkaProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;

@Configuration
@RequiredArgsConstructor
public class KafkaReactorConfig {

    private final ReceiverOptions<String, byte[]> base;
    private final AppKafkaProperties props;

    @Bean
    public KafkaReceiver<String, byte[]> kafkaReceiver() {
        var topic = props.getTopics().getEvents().getOrder();
        var ro = (topic == null || topic.isBlank())
                ? base
                : base.subscription(java.util.List.of(topic));
        return KafkaReceiver.create(ro);
    }
}