package com.github.dimitryivaniuta.payment.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;
import lombok.experimental.FieldNameConstants;
import org.springframework.data.annotation.*;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Partitioned outbox row. NOTE: The physical PK is (id, created_on).
 * Spring Data maps {@code id} as the identifier; {@code createdOn} is carried explicitly.
 * Writes/leases are performed via SQL in the outbox store.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("outbox")
public class OutboxRowEntity {

    @Id
    private Long id;

    @Column("aggregate_id")
    private Long aggregateId;

    @Column("aggregate_type")
    private String aggregateType;

    @Column("attempts")
    private Integer attempts;

    @Column("created_at")
    private Instant createdAt;

    @Column("created_on")
    private LocalDate createdOn;

    @Column("event_key")
    private String eventKey;

    @Column("event_type")
    private String eventType;

    @Column("headers_json")
    private String headersJson;

    @Column("lease_until")
    private Instant leaseUntil;

    @Column("payload")
    private String payload;

    @Column("saga_id")
    private UUID sagaId;

    @Column("tenant_id")
    private String tenantId;

    @Column("updated_at")
    private Instant updatedAt;
}
