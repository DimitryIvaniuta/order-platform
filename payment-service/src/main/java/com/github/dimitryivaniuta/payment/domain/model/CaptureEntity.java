package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Partial or full capture for a payment. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("captures")
public class CaptureEntity {

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

    @Column("psp_capture_ref")
    private String pspCaptureRef;

    private CaptureStatus status;

    @Column("tenant_id")
    private String tenantId;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}
