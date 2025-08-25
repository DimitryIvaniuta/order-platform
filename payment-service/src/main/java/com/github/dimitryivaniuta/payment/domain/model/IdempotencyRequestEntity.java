package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Stores processed idempotency keys with cached responses. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("idempotency_request")
public class IdempotencyRequestEntity {

    @Id
    private Long id;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("key_hash")
    private String keyHash; // SHA-256 hex

    @Column("request_fingerprint")
    private String requestFingerprint; // SHA-256 hex

    @Column("response_bytes")
    private byte[] responseBytes;

    @Column("status_code")
    private Integer statusCode;

    @Column("tenant_id")
    private String tenantId;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}
