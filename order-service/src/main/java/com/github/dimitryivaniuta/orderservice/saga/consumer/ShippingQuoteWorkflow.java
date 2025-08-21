package com.github.dimitryivaniuta.orderservice.saga.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.common.kafka.KafkaProperties;
import lombok.RequiredArgsConstructor;
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
class ShippingQuoteWorkflow {
    private final KafkaReceiver<String, byte[]> receiver;
    private final KafkaSender<String, byte[]> sender;
    private final ObjectMapper om;
//    @Value("${saga.topics.events:order.events.v1}") String eventsTopic;
private final KafkaProperties topics;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        receiver.receive()
                .concatMap(rr -> Mono.fromCallable(() -> om.readTree(rr.value()))
                        .flatMap(json -> {
                            if (!"SHIPPING_QUOTE_REQUESTED".equals(json.path("type").asText())) return Mono.empty();

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

                            var pr = new ProducerRecord<>(eventsTopic, String.valueOf(out.get("orderId").asLong()), om.writeValueAsBytes(out));
                            return sender.send(Mono.just(SenderRecord.create(pr, null))).then();
                        })
                        .doFinally(s -> rr.receiverOffset().acknowledge()))
                .subscribe();
    }
}
