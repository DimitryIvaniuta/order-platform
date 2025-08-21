package com.github.dimitryivaniuta.orderservice.service;

import com.github.dimitryivaniuta.orderservice.model.DiscountCodeEntity;
import com.github.dimitryivaniuta.orderservice.model.OrderEntity;
import com.github.dimitryivaniuta.orderservice.model.OrderItemEntity;
import com.github.dimitryivaniuta.orderservice.web.dto.OrderResponse;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

// com.github.dimitryivaniuta.orderservice.service.CartService
@Service
@RequiredArgsConstructor
public class CartService {

    private final SecurityTenantResolver security;
    private final OrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final DiscountCodeRepository discountRepo;  // simple sync validation
    private final TotalsCalculator totals;
    private final OutboxEventFactory outbox;            // you already use it

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
                    item.setColor(req.color());
                    item.setAttributesJson(req.attributes());     // you have JSONB converters
                    item.setQuantity(req.quantity());
                    item.setUnitPrice(req.unitPrice());
                    item.computeLineTotal();

                    return itemRepo.save(item)
                            .then(recalcTotals(cart.getId(), ctx.tenantId()))
                            .flatMap(updated -> outbox.emitCartItemAdded(ctx.tenantId(), updated, ctx.userId(), ctx.correlationId(), item).thenReturn(updated))
                            .map(OrderResponse::fromEntity)
                            .flatMap(resp -> itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), cart.getId()).collectList().map(resp::withItems));
                })
        );
    }

    /** Update item -> recalc -> emit CART_ITEM_UPDATED. */
    public Mono<OrderResponse> updateItem(Long itemId, UpdateCartItemRequest req) {
        return security.current().flatMap(ctx ->
                itemRepo.findByIdAndTenantId(itemId, ctx.tenantId())
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Item not found")))
                        .flatMap(i -> {
                            if (req.quantity() != null) i.setQuantity(req.quantity());
                            if (req.color() != null)    i.setColor(req.color());
                            if (req.attributes() != null) i.setAttributesJson(req.attributes());
                            i.computeLineTotal();
                            return itemRepo.save(i).thenReturn(i.getOrderId());
                        })
                        .flatMap(orderId -> recalcTotals(orderId, ctx.tenantId())
                                .flatMap(updated -> outbox.emitCartItemUpdated(ctx.tenantId(), updated, ctx.userId(), ctx.correlationId()).thenReturn(updated))
                        )
                        .map(OrderResponse::fromEntity)
                        .flatMap(resp -> itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id()).collectList().map(resp::withItems))
        );
    }

    /** Remove item -> recalc -> emit CART_ITEM_REMOVED. */
    public Mono<OrderResponse> removeItem(Long itemId) {
        return security.current().flatMap(ctx ->
                itemRepo.findByIdAndTenantId(itemId, ctx.tenantId())
                        .flatMap(i -> itemRepo.delete(i).thenReturn(i))
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Item not found")))
                        .flatMap(i -> recalcTotals(i.getOrderId(), ctx.tenantId())
                                .flatMap(updated -> outbox.emitCartItemRemoved(ctx.tenantId(), updated, ctx.userId(), ctx.correlationId(), i.getId()).thenReturn(updated))
                        )
                        .map(OrderResponse::fromEntity)
                        .flatMap(resp -> itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id()).collectList().map(resp::withItems))
        );
    }

    /** Apply discount (simple local validation) -> recalc -> emit DISCOUNT_APPLIED or REJECTED. */
    public Mono<OrderResponse> applyDiscount(ApplyDiscountRequest req) {
        return security.current().flatMap(ctx ->
                        getOrCreateCart().flatMap(cart ->
                                discountRepo.findById(req.code())
                                        .filter(DiscountCodeEntity::isActiveNow)
                                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid or expired discount code")))
                                        .flatMap(dc -> itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), cart.getId()).collectList()
                                                .flatMap(items -> {
                                                    var t = totals.compute(items, computeDiscount(dc, items), cart.getShippingFee());
                                                    cart.setDiscountCode(dc.getCode());
                                                    cart.setDiscountAmount(t.discount());
                                                    cart.setSubtotalAmount(t.subtotal());
                                                    cart.setTotalAmount(t.total());
                                                    cart.touchUpdatedAt();
                                                    return orderRepo.save(cart)
                                                            .flatMap(saved -> outbox.emitDiscountApplied(ctx.tenantId(), saved, ctx.userId(), ctx.correlationId(), dc).thenReturn(saved));
                                                })
                                        )
                        )
                ).map(OrderResponse::fromEntity)
                .flatMap(resp -> security.current().flatMap(ctx -> itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id()).collectList().map(resp::withItems)));
    }

    /** Select shipping -> request fee via Kafka (async) -> return current cart; totals adjust on SHIPPING_QUOTED. */
    public Mono<OrderResponse> chooseShipping(ChooseShippingRequest req) {
        return security.current().flatMap(ctx ->
                        getOrCreateCart().flatMap(cart -> {
                            cart.setShippingOption(req.optionCode());
                            cart.touchUpdatedAt();
                            return orderRepo.save(cart)
                                    .flatMap(saved -> outbox.emitShippingQuoteRequested(ctx.tenantId(), saved, ctx.userId(), ctx.correlationId(), req.optionCode()).thenReturn(saved));
                        })
                ).map(OrderResponse::fromEntity)
                .flatMap(resp -> security.current().flatMap(ctx -> itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id()).collectList().map(resp::withItems)));
    }

    /** Checkout -> emits ORDER_CREATE (you already have saga listeners). */
    public Mono<OrderResponse> checkout() {
        return security.current().flatMap(ctx ->
                        getOrCreateCart().flatMap(cart ->
                                outbox.emitOrderCreated(ctx.tenantId(), cart, ctx.userId(), ctx.correlationId())
                                        .thenReturn(cart)
                        )
                ).map(OrderResponse::fromEntity)
                .flatMap(resp -> security.current().flatMap(ctx -> itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id()).collectList().map(resp::withItems)));
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
}
