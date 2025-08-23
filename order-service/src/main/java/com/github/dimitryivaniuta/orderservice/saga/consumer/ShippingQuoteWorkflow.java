package com.github.dimitryivaniuta.orderservice.saga.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.common.kafka.AppKafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
class ShippingQuoteWorkflow {
    private final KafkaReceiver<String, byte[]> receiver;
    private final KafkaSender<String, byte[]> sender;
    private final ObjectMapper om;
    private final AppKafkaProperties kafkaProps;


    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        receiver.receive()
                .concatMap(rr ->
                        Mono.fromCallable(() -> om.readTree(rr.value()))
                                .flatMap(json -> {
                                    if (!"SHIPPING_QUOTE_REQUESTED".equals(json.path("type").asText())) {
                                        // not our event – just ack and skip
                                        rr.receiverOffset().acknowledge();
                                        return Mono.empty();
                                    }

                                    String option = json.path("shippingOption").asText();
                                    BigDecimal fee = switch (option) {
                                        case "EXPRESS" -> new BigDecimal("14.99");
                                        case "STANDARD" -> new BigDecimal("4.99");
                                        default -> new BigDecimal("9.99");
                                    };

                                    var out = om.createObjectNode();
                                    out.put("type", "SHIPPING_QUOTED");
                                    out.put("sagaId", json.path("sagaId").asText());
                                    out.put("tenantId", json.path("tenantId").asText());
                                    out.put("orderId", json.path("orderId").asLong());
                                    out.put("userId", json.path("userId").asText());
                                    out.put("shippingOption", option);
                                    out.put("shippingFee", fee);

                                    return Mono.fromCallable(() -> om.writeValueAsBytes(out)) // <- handles checked exception
                                            .map(bytes -> {
                                                String key = String.valueOf(out.get("orderId").asLong());
                                                ProducerRecord<String, byte[]> pr =
                                                        new ProducerRecord<>(eventsTopic(), key, bytes);
                                                return SenderRecord.create(pr, key);
                                            })
                                            .flatMap(rec -> sender.send(Mono.just(rec)).then());
                                })
                                .doOnError(e -> log.error("ShippingQuoteWorkflow failed", e))
                                .doFinally(sig -> rr.receiverOffset().acknowledge())
                )
                .subscribe();
    }

    private String eventsTopic() {
        // kafka.topics.events.order in AppKafkaProperties
        return kafkaProps.getTopics().getEvents().getOrder();
    }

}
