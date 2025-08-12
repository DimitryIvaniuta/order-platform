package com.github.dimitryivaniuta.gateway.model;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link UserEntity} using Spring Data R2DBC.
 *
 * <p>Provides case-insensitive lookups and a targeted status update to avoid
 * read-modify-write cycles in hot paths.</p>
 */
@Repository
public interface UserRepository extends ReactiveCrudRepository<UserEntity, Long> {

    /** Finds a user by email, case-insensitive (backed by CI index/type in DB). */
    Mono<UserEntity> findByEmailIgnoreCase(String email);

    /** Finds a user by username, case-insensitive. */
    Mono<UserEntity> findByUsernameIgnoreCase(String username);

    /** Checks existence by email (CI). */
    Mono<Boolean> existsByEmailIgnoreCase(String email);

    /** Checks existence by username (CI). */
    Mono<Boolean> existsByUsernameIgnoreCase(String username);

    /** Streams users by status (e.g., LOCKED) for admin/ops tools. */
    Flux<UserEntity> findByStatus(UserStatus status);

    /**
     * Atomic status update (e.g., lock/suspend/activate) without loading the entity.
     * DB trigger updates {@code updated_at}.
     *
     * @param id     user id
     * @param status new status (SMALLINT)
     * @return rows updated (0 or 1)
     */
    @Query("""
      UPDATE users
         SET status = :status,
             updated_at = now()
       WHERE id = :id
      """)
    Mono<Integer> updateStatus(@Param("id") Long id, @Param("status") Short status);

    /**
     * Optional utility: update last_login timestamp for analytics/audit (if such column exists).
     * Keep here as a placeholder; remove if your schema doesn’t include it.
     */
    @Query("UPDATE users SET updated_at = now() WHERE id = :id")
    Mono<Integer> touch(@Param("id") Long id);
}
