package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Chargeback / dispute lifecycle entity. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("disputes")
public class DisputeEntity {

    @Id
    private Long id;

    @Column("amount_minor")
    private long amountMinor;

    @Column("closed_at")
    private Instant closedAt;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("currency_code")
    private String currencyCode;

    @Column("opened_at")
    private Instant openedAt;

    @Column("payment_id")
    private Long paymentId;

    private String psp;

    @Column("psp_dispute_id")
    private String pspDisputeId;

    @Column("reason_code")
    private String reasonCode;

    private DisputeStage stage;

    @Column("tenant_id")
    private String tenantId;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}
