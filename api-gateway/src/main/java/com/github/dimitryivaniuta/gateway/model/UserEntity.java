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
 * Reactive R2DBC entity for the {@code users} table.
 *
 * <p>Matches Flyway DDL: BIGINT identity PK, CI uniques on username/email,
 * password hash (BCrypt), SMALLINT status, and audit timestamps maintained by DB.</p>
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
@Table("users")
public class UserEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creation timestamp (UTC); set by DB default {@code now()}. */
    @Column("created_at")
    private OffsetDateTime createdAt;

    /** Case-insensitive unique email (DB may use CITEXT). */
    @Column("email")
    private String email;

    /** Primary key, BIGINT identity. */
    @Id
    @Column("id")
    private Long id;

    /** BCrypt password hash; never expose in APIs/logs. */
    @Column("password_hash")
    private String passwordHash;

    /** Account status stored as SMALLINT mapped via {@link UserStatusConverters}. */
    @Column("status")
    private UserStatus status;

    /** Last update timestamp (UTC); maintained by DB trigger. */
    @Column("updated_at")
    private OffsetDateTime updatedAt;

    /** Case-insensitive unique username (DB may use CITEXT). */
    @Column("username")
    private String username;
}
