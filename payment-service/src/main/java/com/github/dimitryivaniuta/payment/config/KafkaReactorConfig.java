package com.github.dimitryivaniuta.payment.config;

import com.github.dimitryivaniuta.common.kafka.AppKafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;

import java.util.List;

/**
 * Wires a Reactor-Kafka {@link KafkaReceiver} subscribed to the
 * Order events topic - payment-service listens for commands like
 * PaymentAuthorizeRequested coming from order-service.
 *
 * Relies on common's Kafka auto-config to provide the base ReceiverOptions
 * (bootstrap servers, group id, deserializers, auto-commit=false, etc.).
 *
 * Config keys (single source of truth):
 *   kafka.bootstrap-servers
 *   kafka.group-id
 *   kafka.topics.events.order
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class KafkaReactorConfig {

    /** Base receiver options from common auto-config (already filled with props). */
    private final ReceiverOptions<String, byte[]> base;

    /** Typed Kafka properties (topics, group id, etc.). */
    private final AppKafkaProperties props;

    @Bean
    public KafkaReceiver<String, byte[]> kafkaReceiver() {
        // Subscribe to the Order events topic (payment-service consumes order → payment commands)
        final String orderTopic = props.getTopics().getEvents().getOrder();

        final ReceiverOptions<String, byte[]> ro = (orderTopic == null || orderTopic.isBlank())
                ? base
                : base.subscription(List.of(orderTopic));

        log.info("KafkaReceiver[payment-service] initialized groupId={} topic={}",
                props.getGroupId(), (orderTopic == null || orderTopic.isBlank()) ? "<none>" : orderTopic);

        return KafkaReceiver.create(ro);
    }
}
