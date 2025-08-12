package com.github.dimitryivaniuta.gateway.saga;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for {@link SagaStatusEntity} using Spring Data R2DBC.
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>All methods are fully non-blocking and return Reactor {@link Mono}/{@link Flux} types.</li>
 *   <li>Primary key is a globally unique {@code UUID} (ideally UUIDv7) used for HTTP/Kafka/DB correlation.</li>
 *   <li>Derived queries are used where possible for portability; a single targeted {@code UPDATE} is provided.</li>
 * </ul>
 */
@Repository
public interface SagaStatusRepository extends ReactiveCrudRepository<SagaStatusEntity, UUID> {

    /**
     * Finds the most recent saga records for a tenant (up to 100), ordered by {@code updated_at} descending.
     *
     * @param tenantId tenant identifier
     * @return a stream of recent saga statuses for that tenant
     */
    Flux<SagaStatusEntity> findTop100ByTenantIdOrderByUpdatedAtDesc(String tenantId);

    /**
     * Finds saga records for a tenant filtered by state (e.g., {@code STARTED}, {@code FAILED}),
     * ordered by {@code updated_at} descending.
     *
     * @param tenantId tenant identifier
     * @param state    current saga state to filter by
     * @return a stream of matching saga statuses
     */
    Flux<SagaStatusEntity> findByTenantIdAndStateOrderByUpdatedAtDesc(String tenantId, String state);

    /**
     * Looks up all saga records currently in a given state (any tenant), ordered by {@code updated_at} descending.
     *
     * @param state saga state to filter by
     * @return a stream of matching saga statuses
     */
    Flux<SagaStatusEntity> findByStateOrderByUpdatedAtDesc(String state);

    /**
     * Performs a lightweight state transition update for a specific saga.
     * <p>
     * This avoids a read-modify-write roundtrip when only the state/reason fields need changing.
     * The database trigger will update {@code updated_at}.
     * </p>
     *
     * @param id     saga id
     * @param state  new state (e.g., {@code COMPLETED}, {@code FAILED})
     * @param reason optional human-readable reason (nullable)
     * @return a {@link Mono} emitting the number of rows updated (0 or 1)
     */
    @Query("""
      UPDATE saga_status
         SET state = :state,
             reason = :reason,
             updated_at = now()
       WHERE id = :id
      """)
    Mono<Integer> updateStateAndReason(@Param("id") UUID id,
                                       @Param("state") String state,
                                       @Param("reason") String reason);

    /**
     * Checks if a saga exists by id (shortcut to {@link ReactiveCrudRepository#existsById(Object)} with {@code UUID}).
     *
     * @param id saga id
     * @return {@code true} if a row exists, otherwise {@code false}
     */
    @Override
    Mono<Boolean> existsById(UUID id);
}
