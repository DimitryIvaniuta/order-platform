package com.github.dimitryivaniuta.gateway.saga;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
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

/**
 * Persistent representation of a Saga's current status.
 *
 * <p>This entity maps to the {@code saga_status} table and is updated by the gateway's Kafka
 * consumer as domain events arrive from the microservices participating in the Saga.
 * The latest status is also fanned out to clients via SSE (through {@link SagaEventBus}).</p>
 *
 * <p><strong>Notes:</strong>
 * <ul>
 *   <li>Primary key {@link #id} is the globally unique Saga identifier (UUID, ideally UUIDv7),
 *       used consistently across HTTP, Kafka keys, and DB for correlation.</li>
 *   <li>Timestamps are generated/maintained by the database (via Flyway DDL + trigger); the
 *       gateway does not need to set them explicitly on each update.</li>
 *   <li>All private fields are ordered alphabetically to comply with project checkstyle rules.</li>
 * </ul>
 * </p>
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants                // Generates type-safe field name constants for queries
@Table("saga_status")
public class SagaStatusEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creation timestamp in UTC; assigned by DB default {@code now()}. */
    @Column("created_at")
    private OffsetDateTime createdAt;

    /** Primary Saga identifier (UUID; used as Kafka key and correlation id). */
    @Id
    @Column("id")
    private UUID id;

    /** Optional human-readable reason, typically populated on failure or compensation. */
    @Column("reason")
    private String reason;

    /**
     * Current state of the Saga, e.g. {@code STARTED}, {@code RESERVED}, {@code PAID},
     * {@code SHIPPED}, {@code FAILED}, {@code COMPLETED}.
     */
    @Column("state")
    private String state;

    /** Tenant identifier to which this Saga belongs (multi-tenant isolation). */
    @Column("tenant_id")
    private String tenantId;

    /** Logical type of the Saga, e.g. {@code ORDER_CREATE}. */
    @Column("type")
    private String type;

    /** Last update timestamp in UTC; maintained by DB trigger on each change. */
    @Column("updated_at")
    private OffsetDateTime updatedAt;

    /** User identifier (subject) that initiated the Saga (from JWT {@code sub}). */
    @Column("user_id")
    private String userId;
}
