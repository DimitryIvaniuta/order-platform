package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Partial or full refund for a payment. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("refunds")
public class RefundEntity {

    @Id
    private Long id;

    @Column("amount_minor")
    private long amountMinor;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("currency_code")
    private String currencyCode;

    @Column("payment_id")
    private Long paymentId;

    private String psp;

    @Column("psp_refund_ref")
    private String pspRefundRef;

    @Column("reason_code")
    private String reasonCode;

    private RefundStatus status;

    @Column("tenant_id")
    private String tenantId;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}
