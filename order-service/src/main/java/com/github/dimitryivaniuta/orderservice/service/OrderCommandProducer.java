package com.github.dimitryivaniuta.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.orderservice.saga.msg.OrderCreateCommand;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

@Component
public class OrderCommandProducer {

    private final KafkaSender<String, byte[]> sender;
    private final ObjectMapper objectMapper;
    private final String topic;

    public OrderCommandProducer(
            KafkaSender<String, byte[]> sender,
            ObjectMapper objectMapper,
            @Value("${saga.topics.order-command:order.command.create.v1}") String topic) {
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public Mono<Void> publishCreate(
            String tenantId,
            Long orderId,
            UUID userId,
            String correlationId,
            java.math.BigDecimal amount
    ) {
        UUID sagaId = UUID.randomUUID();
        OrderCreateCommand cmd = new OrderCreateCommand(
                sagaId, tenantId, orderId, userId, amount, Instant.now()
        );

        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(cmd))
                .map(bytes -> {
                    String key = "order:" + orderId;
                    ProducerRecord<String, byte[]> pr = new ProducerRecord<>(topic, key, bytes);
                    // Useful headers for tracing
                    pr.headers()
                            .add(new RecordHeader("X-Correlation-Id", correlationId.getBytes(StandardCharsets.UTF_8)))
                            .add(new RecordHeader("X-Tenant-Id", tenantId.getBytes(StandardCharsets.UTF_8)))
                            .add(new RecordHeader("X-Event-Type", "ORDER_CREATE".getBytes(StandardCharsets.UTF_8)));
                    return SenderRecord.create(pr, key);
                })
                .flatMap(rec -> sender.send(Mono.just(rec)).then())
                .onErrorResume(ex ->
                        Mono.error(new IllegalStateException("Failed to publish order create command", ex)));
    }
}
