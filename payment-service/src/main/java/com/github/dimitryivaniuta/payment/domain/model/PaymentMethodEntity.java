package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import java.util.UUID;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Tokenized payment method metadata (never stores PAN/CVV). */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("payment_methods")
public class PaymentMethodEntity {

    @Id
    private Long id;

    @Column("billing_address")
    private String billingAddress; // JSONB stored as String; map via Jackson if needed

    @Column("billing_name")
    private String billingName;

    private String brand;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("exp_month")
    private Short expMonth;

    @Column("exp_year")
    private Short expYear;

    private String last4;

    private String psp;

    private String token;

    @Column("tenant_id")
    private String tenantId;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("user_id")
    private UUID userId;
}
