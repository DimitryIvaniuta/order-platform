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
 * Reactive R2DBC entity for the {@code user_roles} join table.
 *
 * <p>Matches Flyway DDL: BIGINT identity PK, FKs to {@code users(id)} and {@code roles(id)},
 * and a unique constraint on {@code (user_id, role_id)}. Timestamps are maintained by DB.</p>
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("user_roles")
public class UserRoleEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creation timestamp (UTC); set by DB default {@code now()}. */
    @Column("created_at")
    private OffsetDateTime createdAt;

    /** Primary key, BIGINT identity. */
    @Id
    @Column("id")
    private Long id;

    /** Role FK. */
    @Column("role_id")
    private Long roleId;

    /** Last update timestamp (UTC); maintained by DB trigger. */
    @Column("updated_at")
    private OffsetDateTime updatedAt;

    /** User FK. */
    @Column("user_id")
    private Long userId;
}
