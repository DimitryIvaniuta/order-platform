package com.github.dimitryivaniuta.orderservice.saga.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.orderservice.model.OrderItemRepository;
import com.github.dimitryivaniuta.orderservice.model.OrderRepository;
import com.github.dimitryivaniuta.orderservice.service.TotalsCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class ShippingQuoteConsumer {

    private final KafkaReceiver<String, byte[]> receiver;
    private final ObjectMapper om;
    private final OrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final TotalsCalculator totals;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        receiver.receive()
                .concatMap(rr -> Mono.fromCallable(() -> om.readTree(rr.value()))
                        .flatMap(json -> {
                            if (!"SHIPPING_QUOTED".equals(json.path("type").asText())) return Mono.empty();

                            long orderId = json.path("orderId").asLong();
                            String tenant = json.path("tenantId").asText();
                            BigDecimal fee = new BigDecimal(json.path("shippingFee").asText("0"));

                            return orderRepo.findByIdAndTenantId(orderId, tenant)
                                    .zipWith(itemRepo.findByTenantIdAndOrderId(tenant, orderId).collectList())
                                    .flatMap(tuple -> {
                                        var order = tuple.getT1();
                                        var items = tuple.getT2();
                                        var t = totals.compute(items, order.getDiscountAmount(), fee);
                                        order.setShippingFee(fee);
                                        order.setSubtotalAmount(t.subtotal());
                                        order.setTotalAmount(t.total());
                                        order.touchUpdatedAt();
                                        return orderRepo.save(order);
                                    });
                        })
                        .doFinally(s -> rr.receiverOffset().acknowledge()))
                .subscribe();
    }
}
