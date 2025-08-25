package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import java.util.UUID;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Direct debit / wallet mandate agreement. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("mandates")
public class MandateEntity {

    @Id
    private Long id;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("mandate_ref")
    private String mandateRef;

    private String psp;

    private String scheme;

    private MandateStatus status;

    @Column("tenant_id")
    private String tenantId;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("user_id")
    private UUID userId;
}
