package com.github.dimitryivaniuta.orderservice.service;

import com.github.dimitryivaniuta.orderservice.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.orderservice.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CartService {

    private final SecurityTenantResolver security;
    private final OrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final DiscountCodeRepository discountRepo;  // simple sync validation
    private final TotalsCalculator totals;
    private final OutboxEventFactory outbox;            // you already use it
    private final ObjectMapper om;

    /** Get or create a PENDING order (acts as cart) for current user. */
    public Mono<OrderEntity> getOrCreateCart() {
        return security.current().flatMap(ctx ->
                orderRepo.findFirstByTenantIdAndUserIdAndStatusOrderByIdDesc(ctx.tenantId(), ctx.userId(), OrderStatus.PENDING)
                        .switchIfEmpty(
                                orderRepo.save(OrderEntity.newPending(ctx.tenantId(), ctx.userId(), BigDecimal.ZERO))
                        )
        );
    }

    /** Add item -> recalc -> emit CART_ITEM_ADDED. */
    public Mono<OrderResponse> addItem(AddCartItemRequest req) {
        return security.current().flatMap(ctx ->
                getOrCreateCart().flatMap(cart -> {
                    OrderItemEntity item = new OrderItemEntity();
                    item.setOrderId(cart.getId());
                    item.setTenantId(ctx.tenantId());
                    item.setProductId(req.productId());
                    item.setSku(req.sku());
                    item.setName(req.name());
                    item.setAttributes(req.attributes());
                    item.setQuantity(req.quantity());
                    item.setUnitPrice(req.unitPrice());
                    item.setLineTotal(item.computeLineTotal());

                    return itemRepo.save(item)
                            .then(recalcTotals(cart.getId(), ctx.tenantId()))
                            .flatMap(updated ->
                                    outbox.emitCartItemAdded(ctx.tenantId(), updated.getId(), ctx.userId(), ctx.correlationId(), item)
                                            .thenReturn(updated)
                            )
                            .map(OrderResponse::fromEntity)
                            .flatMap(resp ->
                                    itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), cart.getId())
                                            .map(OrderItemResponse::from)          // <-- map entity -> DTO
                                            .collectList()
                                            .map(resp::withItems)                         // now List<OrderItemResponse>
                            );
                })
        );
    }

    /** Update item -> recalc -> emit CART_ITEM_UPDATED. */
    public Mono<OrderResponse> updateItem(Long itemId, UpdateCartItemRequest req) {
        return security.current().flatMap(ctx ->
                itemRepo.findByIdAndTenantId(itemId, ctx.tenantId())
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Item not found")))
                        .flatMap(i -> {
                            if (req.quantity() != null) {
                                i.setQuantity(req.quantity());
                            }
                            // Map<String,String> -> JSON handled by entity/converter
                            if (req.attributes() != null) {
                                i.setAttributes(req.attributes());
                            }
                            i.computeLineTotal();
                            return itemRepo.save(i).thenReturn(i); // keep the saved item
                        })
                        .flatMap(savedItem ->
                                recalcTotals(savedItem.getOrderId(), ctx.tenantId())
                                        .flatMap(updatedOrder ->
                                                outbox.emitCartItemUpdated(
                                                        ctx.tenantId(),
                                                        updatedOrder.getId(),
                                                        ctx.userId(),
                                                        ctx.correlationId(),
                                                        savedItem
                                                ).thenReturn(updatedOrder)
                                        )
                        )
                        .map(OrderResponse::fromEntity)
                        .flatMap(resp ->
                                itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id())
                                        .map(OrderItemResponse::from)
                                        .collectList()
                                        .map(resp::withItems)
                        )
        );
    }

    /** Remove item -> recalc -> emit CART_ITEM_REMOVED. */
    public Mono<OrderResponse> removeItem(Long itemId) {
        return security.current().flatMap(ctx ->
                itemRepo.findByIdAndTenantId(itemId, ctx.tenantId())
                        .flatMap(i -> itemRepo.delete(i).thenReturn(i))
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Item not found")))
                        .flatMap(orderItem -> recalcTotals(orderItem.getOrderId(), ctx.tenantId())
                                .flatMap(updated -> outbox.emitCartItemRemoved(ctx.tenantId(),
                                                updated.getId(),
                                                ctx.userId(),
                                                ctx.correlationId(),
                                                orderItem)
                                        .thenReturn(updated))
                        )
                        .map(OrderResponse::fromEntity)
                        .flatMap(resp ->
                                itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id())
                                        .map(OrderItemResponse::from)
                                        .collectList()
                                        .map(resp::withItems))
        );
    }

    /** Apply discount (simple local validation) -> recalc -> emit DISCOUNT_APPLIED or REJECTED. */
    public Mono<OrderResponse> applyDiscount(ApplyDiscountRequest req) {
        return security.current().flatMap(ctx ->
                        getOrCreateCart().flatMap(cart ->
                                discountRepo.findById(req.code())
                                        .filter(DiscountCodeEntity::isActiveNow)
                                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid or expired discount code")))
                                        .flatMap(dc -> itemRepo.findByTenantIdAndOrderId(ctx.tenantId(),
                                                        cart.getId()).collectList()
                                                .flatMap(items -> {
                                                    var t = totals.compute(items, computeDiscount(dc, items), cart.getShippingFee());

                                                    cart.setDiscountCode(dc.getCode());
                                                    cart.setDiscountAmount(t.discount());
                                                    cart.setSubtotalAmount(t.subtotal()); // subtotal before discount (your model)
                                                    cart.setTotalAmount(t.total());       // total after discount
                                                    cart.touchUpdatedAt();

                                                    return orderRepo.save(cart)
                                                            .flatMap(saved ->
                                                                    outbox.emitDiscountApplied(
                                                                            ctx.tenantId(),
                                                                            saved.getId(),
                                                                            ctx.userId(),
                                                                            ctx.correlationId(),
                                                                            dc.getCode(),
                                                                            dc.getType().name(),  // or dc.getType().name()
                                                                            t.discount(),
                                                                            t.subtotal(),
                                                                            t.total()
                                                                    ).thenReturn(saved)
                                                            );
                                                })
                                        )
                        )
                )
                .map(OrderResponse::fromEntity)
                .flatMap(resp -> security.current().flatMap(ctx ->
                        itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id())
                                .map(OrderItemResponse::from)
                                .collectList()
                                .map(resp::withItems)));
    }


    /** Select shipping -> request fee via Kafka (async) -> return current cart; totals adjust on SHIPPING_QUOTED. */
    public Mono<OrderResponse> chooseShipping(ChooseShippingRequest req) {
        return security.current().flatMap(ctx ->
                        getOrCreateCart().flatMap(cart -> {
                            cart.setShippingOption(req.optionCode());
                            cart.touchUpdatedAt();
                            return orderRepo.save(cart)
                                    .flatMap(saved -> outbox.emitShippingQuoteRequested(ctx.tenantId(),
                                            saved.getId(),
                                            ctx.userId(),
                                            ctx.correlationId(),
                                            req.optionCode(),
                                            req.weight(),
                                            req.country(),
                                            req.postalCode()).thenReturn(saved));
                        })
                ).map(OrderResponse::fromEntity)
                .flatMap(resp -> security.current().flatMap(ctx ->
                        itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id())
                                .map(OrderItemResponse::from)
                                .collectList()
                                .map(resp::withItems)));
    }

    /** Checkout -> emits ORDER_CREATE (you already have saga listeners). */
    public Mono<OrderResponse> checkout() {
        return security.current().flatMap(ctx ->
                        getOrCreateCart().flatMap(cart ->
                                outbox.emitOrderCreated(ctx.tenantId(), cart, ctx.userId(), ctx.correlationId())
                                        .thenReturn(cart)
                        )
                ).map(OrderResponse::fromEntity)
                .flatMap(resp -> security.current().flatMap(ctx ->
                        itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id())
                                .map(OrderItemResponse::from)
                                .collectList()
                                .map(resp::withItems)));
    }

    // ---- helpers ----
    private Mono<OrderEntity> recalcTotals(Long orderId, String tenant) {
        return itemRepo.findByTenantIdAndOrderId(tenant, orderId).collectList()
                .flatMap(items -> orderRepo.findByIdAndTenantId(orderId, tenant)
                        .flatMap(order -> {
                            var t = totals.compute(items, order.getDiscountAmount(), order.getShippingFee());
                            order.setSubtotalAmount(t.subtotal());
                            order.setTotalAmount(t.total());
                            order.touchUpdatedAt();
                            return orderRepo.save(order);
                        }));
    }

    private BigDecimal computeDiscount(DiscountCodeEntity dc, List<OrderItemEntity> items) {
        BigDecimal subtotal = items.stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return switch (dc.getType()) {
            case PERCENT -> subtotal.multiply(dc.getValue()).divide(BigDecimal.valueOf(100));
            case AMOUNT  -> dc.getValue();
        };
    }

    public String getAttributesJson(Map<String,String> attrs) {
        try {
            return (attrs == null || attrs.isEmpty())
                    ? null
                    : om.writeValueAsString(attrs);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize item attributes", e);
        }
    }

    public Mono<OrderResponse> currentCart() {
        return getOrCreateCart() // OrderEntity (the cart)
                .flatMap(cart ->
                        security.current().flatMap(ctx ->
                                itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), cart.getId())
                                        .map(OrderItemResponse::from)     // <-- map entity -> DTO
                                        .collectList()
                                        .map(items ->
                                                OrderResponse.fromEntity(cart)
                                                        .withItems(items))
                        )
                );
    }

}
