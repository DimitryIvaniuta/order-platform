package com.github.dimitryivaniuta.orderservice.service;

import com.github.dimitryivaniuta.orderservice.model.OrderEntity;
import com.github.dimitryivaniuta.orderservice.model.OrderRepository;
import com.github.dimitryivaniuta.orderservice.model.OrderStatus;
import com.github.dimitryivaniuta.orderservice.web.dto.CreateOrderRequest;
import com.github.dimitryivaniuta.orderservice.web.dto.OrderResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class OrderService {

    private final OrderRepository repo;
    private final OrderCommandProducer producer;
    private final SecurityTenantResolver security; // defined below
    private final OrderItemService itemService;

    public OrderService(OrderRepository repo, OrderCommandProducer producer, SecurityTenantResolver security, OrderItemService itemService) {
        this.repo = repo;
        this.producer = producer;
        this.security = security;
        this.itemService = itemService;
    }

    public Mono<OrderResponse> createOrder(CreateOrderRequest req) {
        return security.current()
                .flatMap(ctx -> {
                    OrderEntity toSave = OrderEntity.newPending(ctx.tenantId(), ctx.userId(), req.totalAmount());
                    return repo.save(toSave)
                            .flatMap(saved ->
                                    producer.publishCreate(
                                            ctx.tenantId(),
                                            saved.getId(),
                                            ctx.userId(),
                                            ctx.correlationId(),
                                            saved.getTotalAmount()
                                    ).thenReturn(saved)
                            );
                })
                .map(OrderResponse::fromEntity);
    }

    public Mono<OrderResponse> getById(Long id) {
        return security.current()
                .flatMap(ctx ->
                        repo.findByIdAndTenantId(id, ctx.tenantId())
                                .switchIfEmpty(Mono.error(new IllegalArgumentException("Order not found")))
                                .map(OrderResponse::fromEntity)
                                .flatMap(resp -> itemService.list(ctx.tenantId(), id)
                                        .collectList()
                                        .map(resp::withItems))
                );
    }

    public Mono<OrderResponse> markPaid(Long id) {
        return security.current()
                .flatMap(ctx -> repo.findByIdAndTenantId(id, ctx.tenantId()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Order not found")))
                .flatMap(order -> {
                    order.setStatus(OrderStatus.PAID);
                    return repo.save(order);
                })
                .map(OrderResponse::fromEntity);
    }
}
