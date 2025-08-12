package com.github.dimitryivaniuta.gateway.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
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
 * Reactive R2DBC entity for the {@code roles} table.
 *
 * <p>Matches Flyway DDL: BIGINT identity PK, case-insensitive unique {@code name},
 * and audit timestamps maintained by the database.</p>
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("roles")
public class RoleEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creation timestamp (UTC); set by DB default {@code now()}. */
    @Column("created_at")
    private OffsetDateTime createdAt;

    /** Primary key, BIGINT identity. */
    @Id
    @Column("id")
    private Long id;

    /** Role name (e.g., {@code ADMIN}, {@code ORDER_WRITE}); CI unique in DB. */
    @Column("name")
    private String name;

    /** Last update timestamp (UTC); maintained by DB trigger. */
    @Column("updated_at")
    private OffsetDateTime updatedAt;
}
