package com.github.dimitryivaniuta.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.orderservice.model.OrderEntity;
import com.github.dimitryivaniuta.orderservice.model.OrderItemEntity;
import com.github.dimitryivaniuta.orderservice.outbox.OutboxStore;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxEventFactory {

    private static final String AGGREGATE_TYPE = "ORDER";

    private final OutboxStore outbox;
    private final ObjectMapper om;

    public Mono<OutboxRow> emitOrderCreated(String tenant, OrderEntity order, UUID userId, String corrId) {
        UUID sagaId = sagaIdForOrder(tenant, order.getId());
        var payload = Map.<String, Object>of(
                "type", "ORDER_CREATED",
                "tenantId", tenant,
                "orderId", order.getId(),
                "userId", userId != null ? userId.toString() : null,
                "totalAmount", order.getTotalAmount(),
                "status", order.getStatus().name(),
                "createdAt", Instant.now().toString()
        );
        return write(tenant, sagaId, order.getId(), "ORDER_CREATED", payload, corrId);
    }

    public Mono<OutboxRow> emitItemsAdded(String tenant, Long orderId, UUID userId, String corrId, List<OrderItemEntity> items) {
        UUID sagaId = sagaIdForOrder(tenant, orderId);
        var payload = Map.of(
                "type", "ORDER_ITEMS_ADDED",
                "tenantId", tenant,
                "orderId", orderId,
                "userId", userId != null ? userId.toString() : null,
                "items", items.stream().map(this::toItemJson).toList(),
                "at", Instant.now().toString()
        );
        return write(tenant, sagaId, orderId, "ORDER_ITEMS_ADDED", payload, corrId);
    }

    public Mono<OutboxRow> emitItemsUpdated(String tenant, Long orderId, UUID userId, String corrId, List<OrderItemEntity> items) {
        UUID sagaId = sagaIdForOrder(tenant, orderId);
        var payload = Map.of(
                "type", "ORDER_ITEMS_UPDATED",
                "tenantId", tenant,
                "orderId", orderId,
                "userId", userId != null ? userId.toString() : null,
                "items", items.stream().map(this::toItemJson).toList(),
                "at", Instant.now().toString()
        );
        return write(tenant, sagaId, orderId, "ORDER_ITEMS_UPDATED", payload, corrId);
    }

    public Mono<OutboxRow> emitItemsRemoved(String tenant, Long orderId, UUID userId, String corrId, List<OrderItemEntity> items) {
        UUID sagaId = sagaIdForOrder(tenant, orderId);
        var payload = Map.of(
                "type", "ORDER_ITEMS_REMOVED",
                "tenantId", tenant,
                "orderId", orderId,
                "userId", userId != null ? userId.toString() : null,
                "items", items.stream().map(this::toItemJson).toList(),
                "at", Instant.now().toString()
        );
        return write(tenant, sagaId, orderId, "ORDER_ITEMS_REMOVED", payload, corrId);
    }

    public Mono<OutboxRow> emitCartItemAdded(String tenant, Long orderId, UUID userId, String corrId, OrderItemEntity item) {
        UUID sagaId = sagaIdForOrder(tenant, orderId);
        var payload = Map.<String, Object>of(
                "type", "CART_ITEM_ADDED",
                "tenantId", tenant,
                "orderId", orderId,
                "userId", userId != null ? userId.toString() : null,
                "item", toItemJson(item),
                "at", Instant.now().toString()
        );
        return write(tenant, sagaId, orderId, "CART_ITEM_ADDED", payload, corrId);
    }

    public Mono<OutboxRow> emitCartItemUpdated(String tenant, Long orderId, UUID userId, String corrId, OrderItemEntity item) {
        UUID sagaId = sagaIdForOrder(tenant, orderId);
        var payload = Map.<String, Object>of(
                "type", "CART_ITEM_UPDATED",
                "tenantId", tenant,
                "orderId", orderId,
                "userId", userId != null ? userId.toString() : null,
                "item", toItemJson(item),
                "at", Instant.now().toString()
        );
        return write(tenant, sagaId, orderId, "CART_ITEM_UPDATED", payload, corrId);
    }

    public Mono<OutboxRow> emitCartItemRemoved(String tenant, Long orderId, UUID userId, String corrId, OrderItemEntity itemBeforeDelete) {
        UUID sagaId = sagaIdForOrder(tenant, orderId);
        var payload = Map.<String, Object>of(
                "type", "CART_ITEM_REMOVED",
                "tenantId", tenant,
                "orderId", orderId,
                "userId", userId != null ? userId.toString() : null,
                "item", toItemJson(itemBeforeDelete),
                "at", Instant.now().toString()
        );
        return write(tenant, sagaId, orderId, "CART_ITEM_REMOVED", payload, corrId);
    }

    public Mono<OutboxRow> emitDiscountApplied(
            String tenant,
            Long orderId,
            UUID userId,
            String corrId,
            String discountCode,
            String discountKind,                // e.g. "PERCENT" or "AMOUNT"
            java.math.BigDecimal discountAmount,
            java.math.BigDecimal subtotalBefore,
            java.math.BigDecimal totalAfter
    ) {
        UUID sagaId = sagaIdForOrder(tenant, orderId);
        var payload = Map.<String, Object>of(
                "type", "DISCOUNT_APPLIED",
                "tenantId", tenant,
                "orderId", orderId,
                "userId", userId != null ? userId.toString() : null,
                "code", discountCode,
                "kind", discountKind,
                "discountAmount", discountAmount,
                "subtotalBefore", subtotalBefore,
                "totalAfter", totalAfter,
                "at", Instant.now().toString()
        );
        return write(tenant, sagaId, orderId, "DISCOUNT_APPLIED", payload, corrId);
    }

    // ----------------------- NEW: shipping quote request -----------------------

    public Mono<OutboxRow> emitShippingQuoteRequested(
            String tenant,
            Long orderId,
            UUID userId,
            String corrId,
            String serviceLevel,                // e.g. "STANDARD", "EXPRESS"
            java.math.BigDecimal weight,        // optional
            String country,                     // optional
            String postalCode                   // optional
    ) {
        UUID sagaId = sagaIdForOrder(tenant, orderId);
        var payload = Map.<String, Object>of(
                "type", "SHIPPING_QUOTE_REQUESTED",
                "tenantId", tenant,
                "orderId", orderId,
                "userId", userId != null ? userId.toString() : null,
                "serviceLevel", serviceLevel,
                "weight", weight,
                "country", country,
                "postalCode", postalCode,
                "at", Instant.now().toString()
        );
        return write(tenant, sagaId, orderId, "SHIPPING_QUOTE_REQUESTED", payload, corrId);
    }

    // ----------------------- helpers -----------------------

    private Map<String, Object> toItemJson(OrderItemEntity i) {
        return Map.of(
                "id", i.getId(),
                "productId", i.getProductId() != null ? i.getProductId().toString() : null,
                "sku", i.getSku(),
                "name", i.getName(),
                "attributes", i.getAttributes(),              // JSONB string (optional)
                "quantity", i.getQuantity(),
                "unitPrice", i.getUnitPrice(),
                "lineTotal", i.getLineTotal()
        );
    }

    private Mono<OutboxRow> write(
            String tenant,
            UUID sagaId,
            Long orderId,
            String eventType,
            Object payload,
            String corrId
    ) {
        try {
            String json = om.writeValueAsString(payload);
            // Minimal, useful headers; expand as needed (e.g., user-id, tenant-id, mt).
            Map<String, String> headers = Map.of(
                    "saga-id", sagaId != null ? sagaId.toString() : "",
                    "correlation-id", corrId != null ? corrId : "",
                    "event-type", eventType,
                    "tenant-id", tenant
            );

            return outbox.insertRow(
                    tenant,
                    sagaId,
                    AGGREGATE_TYPE,
                    orderId,
                    eventType,
                    String.valueOf(orderId), // stable partition key
                    json,
                    headers
            );
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    /** Deterministic saga id per (tenant, order id). */
    private static UUID sagaIdForOrder(String tenant, Long orderId) {
        var seed = (tenant + "|ORDER|" + orderId).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(seed);
    }

}
