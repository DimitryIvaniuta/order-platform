package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Individual authorization attempt for a payment. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("payment_attempts")
public class PaymentAttemptEntity {

    @Id
    private Long id;

    @Column("attempt_no")
    private int attemptNo;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("failure_code")
    private String failureCode;

    @Column("failure_reason")
    private String failureReason;

    @Column("idempotency_key")
    private String idempotencyKey;

    @Column("next_action_json")
    private String nextActionJson;

    @Column("payment_id")
    private Long paymentId;

    private String psp;

    @Column("psp_ref")
    private String pspRef;

    @Column("request_fingerprint")
    private String requestFingerprint;

    private AttemptStatus status;

    @Column("tenant_id")
    private String tenantId;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}
