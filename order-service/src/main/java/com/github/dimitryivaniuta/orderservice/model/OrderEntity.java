package com.github.dimitryivaniuta.orderservice.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldNameConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("orders")
public class OrderEntity {

    @Id
    private Long id;

    @Column("tenant_id")
    private String tenantId;

    @Column("user_id")
    private UUID userId;

    @Column("subtotal_amount")
    private BigDecimal subtotalAmount = BigDecimal.ZERO;

    @Column("discount_amount")
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column("discount_code")
    private String discountCode;

    @Column("shipping_fee")
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Column("shipping_option")
    private String shippingOption;

    @Column("total_amount")
    private BigDecimal totalAmount;

    @Column("status")
    private OrderStatus status;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public static OrderEntity newPending(String tenantId, UUID userId, BigDecimal amount) {
        return OrderEntity.builder()
                .tenantId(tenantId)
                .userId(userId)
                .totalAmount(amount)
                .status(OrderStatus.PENDING)
                .build();
    }

    /** bump the optimistic timestamp */
    public void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    /** just set the code; amount is set via applyDiscountAmount(...) */
    public void setDiscountCode(String code) {
        this.discountCode = code;
        touchUpdatedAt();
    }
}
