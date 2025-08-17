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

    @Column("total_amount")
    private BigDecimal totalAmount;

    private OrderStatus status;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public static OrderEntity newPending(String tenantId, UUID userId, BigDecimal amount) {
        return new OrderEntity(
                null, tenantId, userId, amount, OrderStatus.PENDING, null, null
        );
    }

}
