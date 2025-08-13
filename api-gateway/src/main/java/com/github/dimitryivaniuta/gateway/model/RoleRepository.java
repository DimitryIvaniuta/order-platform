package com.github.dimitryivaniuta.gateway.model;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

/**
 * Reactive repository for {@link RoleEntity}.
 *
 * <p>Provides case-insensitive lookups and convenience queries.</p>
 */
@Repository
public interface RoleRepository extends ReactiveCrudRepository<RoleEntity, Long> {

    /** Finds a role by name, case-insensitive. */
    Mono<RoleEntity> findByNameIgnoreCase(String name);

    /** Checks existence of a role by name, case-insensitive. */
    Mono<Boolean> existsByNameIgnoreCase(String name);

    /** Finds all roles whose names are in the given set (case-sensitive by default). */
    @Query("SELECT id, name, created_at, updated_at FROM roles WHERE LOWER(name) IN (:names)")
    Flux<RoleEntity> findByNameIgnoreCaseIn(@Param("names") Collection<String> names);
}
