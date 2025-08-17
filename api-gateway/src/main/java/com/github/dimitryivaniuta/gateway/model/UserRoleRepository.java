package com.github.dimitryivaniuta.gateway.model;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive repository for {@link UserRoleEntity}.
 *
 * <p>Includes idempotent assignment/removal helpers and lookups by user/role.</p>
 */
@Repository
public interface UserRoleRepository extends ReactiveCrudRepository<UserRoleEntity, Long> {

    /** Lists all user-role links for a given user id. */
    Flux<UserRoleEntity> findByUserId(Long userId);

    /** Lists all user-role links for a given role id. */
    Flux<UserRoleEntity> findByRoleId(Long roleId);

    /** Checks if a given user already has a particular role. */
    Mono<Boolean> existsByUserIdAndRoleId(Long userId, Long roleId);

    /**
     * Assigns a role to a user idempotently (no-op if it already exists).
     *
     * @return rows inserted (0 if already present, 1 if created)
     */
    @Query("""
      INSERT INTO user_roles (user_id, role_id)
      VALUES (:userId, :roleId)
      ON CONFLICT (user_id, role_id) DO NOTHING
      """)
    Mono<Integer> assign(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /**
     * Removes a role from a user.
     *
     * @return rows deleted (0 or 1)
     */
    @Query("""
      DELETE FROM user_roles
       WHERE user_id = :userId AND role_id = :roleId
      """)
    Mono<Integer> unassign(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /**
     * Convenience: returns role names for a user (join on roles).
     */
    @Query("""
      SELECT r.name
        FROM user_roles ur
        JOIN roles r ON r.id = ur.role_id
       WHERE ur.user_id = :userId
      """)
    Flux<String> roleNamesForUser(@Param("userId") Long userId);

    /**
     * Convenience: returns user ids for a given role name.
     */
    @Query("""
      SELECT ur.user_id
        FROM user_roles ur
        JOIN roles r ON r.id = ur.role_id
       WHERE LOWER(r.name) = LOWER(:roleName)
      """)
    Flux<Long> userIdsForRoleName(@Param("roleName") String roleName);


    // simple, strongly-typed row projection
    record RoleNameRow(@Column("name") String name) {}

    @Query("""
        SELECT r.name AS name
        FROM roles r
        JOIN user_roles ur ON ur.role_id = r.id
        WHERE ur.user_id = :userId
    """)
    Flux<RoleNameRow> findRoleNamesByUserId(@Param("userId") Long userId);
}
