package com.github.dimitryivaniuta.orderservice.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@NoArgsConstructor
@Table("order_items")
public class OrderItemEntity {

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    @Column("order_id")
    private Long orderId;

    @Column("product_id")
    private UUID productId;

    private String sku;

    private String name;

    private Integer quantity;

    @Column("unit_price")
    private BigDecimal unitPrice;

    @Column("line_total")
    private BigDecimal lineTotal;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    public static OrderItemEntity of(
            String tenantId, Long orderId, UUID productId, String sku, String name,
            Integer qty, BigDecimal unitPrice) {

        OrderItemEntity e = new OrderItemEntity();
        e.setTenantId(tenantId);
        e.setOrderId(orderId);
        e.setProductId(productId);
        e.setSku(sku);
        e.setName(name);
        e.setQuantity(qty);
        e.setUnitPrice(unitPrice);
        e.setLineTotal(unitPrice.multiply(BigDecimal.valueOf(qty)));
        return e;
    }
}
