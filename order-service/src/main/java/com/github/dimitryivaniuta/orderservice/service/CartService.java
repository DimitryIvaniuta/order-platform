package com.github.dimitryivaniuta.orderservice.service;

import com.github.dimitryivaniuta.orderservice.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.orderservice.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CartService {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_RM = RoundingMode.HALF_UP;
    private static final int MAX_ATTRS = 20;
    private static final Pattern KEY_RE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final int MAX_VAL_LEN = 256;

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
    @Transactional
    public Mono<OrderResponse> addItem(AddCartItemRequest req) {
        return security.current().flatMap(ctx ->
                getOrCreateCart().flatMap(cart -> {

                    var attrs = validateAttributes(req.attributes());
                    var qty   = requirePositiveQty(req.quantity());
                    var price = requireNonNegative(req.unitPrice());

                    OrderItemEntity item = new OrderItemEntity();
                    item.setOrderId(cart.getId());
                    item.setTenantId(ctx.tenantId());
                    item.setProductId(req.productId());
                    item.setSku(req.sku());
                    item.setName(req.name());
                    item.setAttributes(attrs);
                    item.setQuantity(qty);
                    item.setUnitPrice(price);
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
    @Transactional
    public Mono<OrderResponse> updateItem(Long itemId, UpdateCartItemRequest req) {
        return security.current().flatMap(ctx ->
                itemRepo.findByIdAndTenantId(itemId, ctx.tenantId())
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Item not found")))
                        .flatMap(i -> {
                            if (req.quantity() != null) {
                                i.setQuantity(requirePositiveQty(req.quantity()));
                            }
                            // Map<String,String> -> JSON handled by entity/converter
                            if (req.attributes() != null) {
                                i.setAttributes(validateAttributes(req.attributes()));
                            }
                            i.setLineTotal(i.computeLineTotal());
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
    @Transactional
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
    @Transactional
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
    @Transactional
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
    @Transactional
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

        BigDecimal raw = switch (dc.getType()) {
            case PERCENT -> subtotal
                    .multiply(dc.getValue())   // e.g. value=15 means 15%
                    .movePointLeft(2);         // exact  ÷100 without rounding mode
            case AMOUNT  -> dc.getValue();
        };

        // Guardrails + final scaling for DB
        if (raw.signum() < 0) raw = BigDecimal.ZERO;
        if (raw.compareTo(subtotal) > 0) raw = subtotal;

        return raw.setScale(MONEY_SCALE, MONEY_RM);
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


    /** Validates and normalizes attributes: key charset/length, value length, max entries. */
    private Map<String,String> validateAttributes(Map<String,String> attrs) {
        if (attrs == null || attrs.isEmpty()) return Map.of();
        if (attrs.size() > MAX_ATTRS) throw new IllegalArgumentException("Too many attributes");
        return attrs.entrySet().stream()
                .peek(e -> {
                    if (!KEY_RE.matcher(e.getKey()).matches())
                        throw new IllegalArgumentException("Bad attribute key: " + e.getKey());
                    var v = e.getValue();
                    if (v == null || v.isBlank() || v.length() > MAX_VAL_LEN)
                        throw new IllegalArgumentException("Bad attribute value for key: " + e.getKey());
                })
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static int requirePositiveQty(Integer q) {
        if (q == null || q <= 0) throw new IllegalArgumentException("quantity must be > 0");
        return q;
    }
    private static BigDecimal requireNonNegative(BigDecimal p) {
        if (p == null || p.signum() < 0) throw new IllegalArgumentException("unitPrice must be >= 0");
        return p;
    }

}
