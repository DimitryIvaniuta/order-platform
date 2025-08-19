package com.github.dimitryivaniuta.orderservice.service;

import com.github.dimitryivaniuta.orderservice.model.OrderEntity;
import com.github.dimitryivaniuta.orderservice.model.OrderItemEntity;
import com.github.dimitryivaniuta.orderservice.model.OrderItemRepository;
import com.github.dimitryivaniuta.orderservice.model.OrderRepository;
import com.github.dimitryivaniuta.orderservice.model.OrderStatus;
import com.github.dimitryivaniuta.orderservice.web.dto.OrderItemRequest;
import com.github.dimitryivaniuta.orderservice.web.dto.OrderItemResponse;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxRow;
import com.github.dimitryivaniuta.orderservice.web.dto.UpdateOrderItemRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class OrderItemService {

    private final OrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final ReactiveTransactionManager txm;
    private final OutboxEventFactory outbox;

    public Flux<OrderItemResponse> list(String tenant, Long orderId) {
        return ensureOrderOwned(tenant, orderId)
                .thenMany(itemRepo.findAllByOrderIdAndTenantId(orderId, tenant))
                .map(OrderItemResponse::from);
    }

    public Mono<OrderItemResponse> addItem(String tenant, Long orderId, OrderItemRequest req, UUID userId, String correlationId) {
        return ensureOrderMutable(tenant, orderId)
                .flatMap(order -> {
                    OrderItemEntity e = OrderItemEntity.of(
                            tenant, orderId, req.productId(), req.sku(), req.name(),
                            req.quantity(), req.unitPrice()
                    );
                    var tx = TransactionalOperator.create(txm);
                    return itemRepo.save(e)
                            .then(recalcAndUpdateTotal(order))
                            .then(outbox.emitItemsAdded(tenant, orderId, userId, correlationId, List.of(e)))
                            .thenReturn(e)
                            .map(OrderItemResponse::from)
                            .as(tx::transactional);
                });
    }

    public Mono<OrderItemResponse> updateItem(String tenant, Long orderId, Long itemId, UpdateOrderItemRequest req, UUID userId, String correlationId) {
        return ensureOrderMutable(tenant, orderId)
                .then(itemRepo.findById(itemId)
                        .filter(e -> Objects.equals(e.getOrderId(), orderId) && Objects.equals(e.getTenantId(), tenant))
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Item not found"))))
                .flatMap(e -> {
                    if (req.quantity() != null) e.setQuantity(req.quantity());
                    if (req.unitPrice() != null) e.setUnitPrice(req.unitPrice());
                    if (req.name() != null) e.setName(req.name());
                    e.setLineTotal(e.getUnitPrice().multiply(BigDecimal.valueOf(e.getQuantity())));
                    var tx = TransactionalOperator.create(txm);
                    return itemRepo.save(e)
                            .then(recalcAndUpdateTotal(orderId, tenant))
                            .then(outbox.emitItemsUpdated(tenant, orderId, userId, correlationId, List.of(e)))
                            .thenReturn(e)
                            .map(OrderItemResponse::from)
                            .as(tx::transactional);
                });
    }

    public Mono<OutboxRow> removeItem(String tenant, Long orderId, Long itemId, UUID userId, String correlationId) {
        return ensureOrderMutable(tenant, orderId)
                .then(itemRepo.findById(itemId)
                        .filter(e -> Objects.equals(e.getOrderId(), orderId) && Objects.equals(e.getTenantId(), tenant))
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Item not found"))))
                .flatMap(e -> {
                    var tx = TransactionalOperator.create(txm);
                    return itemRepo.deleteById(e.getId())
                            .then(recalcAndUpdateTotal(orderId, tenant))
                            .then(outbox.emitItemsRemoved(tenant, orderId, userId, correlationId, List.of(e)))
                            .as(tx::transactional);
                });
    }

    /** Helper used by OrderService when creating an order with items. */
    public Mono<List<OrderItemEntity>> bulkInsert(String tenant, Long orderId, List<OrderItemRequest> items) {
        return Flux.fromIterable(items)
                .map(i -> OrderItemEntity.of(tenant, orderId, i.productId(), i.sku(), i.name(), i.quantity(), i.unitPrice()))
                .collectList()
                .flatMap(list -> itemRepo.saveAll(list).collectList());
    }

    // ---------- internals ----------

    private Mono<OrderEntity> ensureOrderOwned(String tenant, Long orderId) {
        return orderRepo.findById(orderId)
                .filter(o -> Objects.equals(o.getTenantId(), tenant))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Order not found")));
    }

    private Mono<OrderEntity> ensureOrderMutable(String tenant, Long orderId) {
        return ensureOrderOwned(tenant, orderId)
                .flatMap(o -> {
                    // block modifications after PAID/REJECTED/CANCELLED
                    if (o.getStatus() == OrderStatus.PAID || o.getStatus() == OrderStatus.REJECTED || o.getStatus() == OrderStatus.CANCELLED) {
                        return Mono.error(new IllegalStateException("Order is not modifiable in status " + o.getStatus()));
                    }
                    return Mono.just(o);
                });
    }

    private Mono<Void> recalcAndUpdateTotal(Long orderId, String tenant) {
        return orderRepo.findById(orderId)
                .filter(o -> Objects.equals(o.getTenantId(), tenant))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Order not found")))
                .flatMap(o -> itemRepo.sumTotals(orderId, tenant)
                        .defaultIfEmpty(BigDecimal.ZERO)
                        .flatMap(sum -> {
                            o.setTotalAmount(sum);
                            return orderRepo.save(o).then();
                        }));
    }

    private Mono<Void> recalcAndUpdateTotal(OrderEntity order) {
        return itemRepo.sumTotals(order.getId(), order.getTenantId())
                .defaultIfEmpty(BigDecimal.ZERO)
                .flatMap(sum -> {
                    order.setTotalAmount(sum);
                    return orderRepo.save(order).then();
                });
    }
}
