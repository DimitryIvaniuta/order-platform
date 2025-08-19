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

//    private final OutboxRepository outbox;
    private final OutboxStore outbox;
    private final ObjectMapper om;

    public Mono<OutboxRow> emitOrderCreated(String tenant, OrderEntity order, UUID userId, String corrId) {
        UUID sagaId = sagaIdForOrder(tenant, order.getId());
        var payload = Map.of(
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

    // ---------- helpers ----------

    private Map<String, Object> toItemJson(OrderItemEntity i) {
        return Map.of(
                "id", i.getId(),
                "productId", i.getProductId() != null ? i.getProductId().toString() : null,
                "sku", i.getSku(),
                "name", i.getName(),
                "quantity", i.getQuantity(),
                "unitPrice", i.getUnitPrice(),
                "lineTotal", i.getLineTotal()
        );
    }

    /**
     * Generic writer to the partitioned outbox. We let the repository derive sagaId when null.
     * eventKey = String.valueOf(orderId) to keep partitioning/ordering stable across all events of this order.
     */
    private Mono<OutboxRow> write(String tenant,
                                  UUID sagaId,
                                  Long orderId,
                                  String eventType,
                                  Object payload,
                                  String corrId) {
        try {
            String json = om.writeValueAsString(payload);
            // Minimal, useful headers; expand as needed (e.g., user-id, tenant-id, mt).
            Map<String, String> headers = Map.of(
                    "saga-id", sagaId.toString(),
                    "correlation-id", corrId != null ? corrId : "",
                    "event-type", eventType
            );

            return outbox.insertRow(
                    tenant,
                    sagaId,                  // let repo derive if null
                    AGGREGATE_TYPE,
                    orderId,
                    eventType,
                    String.valueOf(orderId), // eventKey
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
