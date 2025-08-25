package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import java.util.UUID;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.annotation.Transient;

/**
 * Payment aggregate root. Stores money in minor units and currency as ISO-4217 code.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("payments")
public class PaymentEntity {

    /** Surrogate PK. */
    @Id
    private Long id;

    @Column("amount_minor")
    private long amountMinor;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("currency_code")
    private String currencyCode;

    @Column("failure_code")
    private String failureCode;

    @Column("failure_reason")
    private String failureReason;

    @Column("next_action_json")
    private String nextActionJson;

    @Column("order_id")
    private Long orderId;

    /** Provider code (FAKE/STRIPE/ADYEN...). */
    private String psp;

    /** Provider reference for this payment. */
    @Column("psp_ref")
    private String pspRef;

    @Column("saga_id")
    private UUID sagaId;

    private PaymentStatus status;

    @Column("tenant_id")
    private String tenantId;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("user_id")
    private UUID userId;

    @Transient private java.util.List<PaymentAttemptEntity> attempts;

    @Transient private java.util.List<CaptureEntity> captures;

    @Transient private java.util.List<RefundEntity> refunds;
}
