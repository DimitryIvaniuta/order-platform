package com.github.dimitryivaniuta.payment.domain.repo;

import com.github.dimitryivaniuta.payment.domain.model.PaymentEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PaymentRepository extends ReactiveCrudRepository<PaymentEntity, Long> {

    // latest payment for a saga in tenant (idempotency)
    @Query("""
           SELECT * FROM payments
            WHERE tenant_id = :tenant AND saga_id = :saga
            ORDER BY id DESC
            LIMIT 1
           """)
    Mono<PaymentEntity> findLatestByTenantAndSaga(String tenant, UUID saga);

    // latest payment for an order in tenant
    @Query("""
           SELECT * FROM payments
            WHERE tenant_id = :tenant AND order_id = :orderId
            ORDER BY id DESC
            LIMIT 1
           """)
    Mono<PaymentEntity> findLatestByTenantAndOrder(String tenant, Long orderId);

    // list all payments for an order in tenant (newest first)
    @Query("""
           SELECT * FROM payments
            WHERE tenant_id = :tenant AND order_id = :orderId
            ORDER BY id DESC
           """)
    Flux<PaymentEntity> findAllByTenantAndOrder(String tenant, Long orderId);

    // secure single read by id+tenant
    @Query("""
           SELECT * FROM payments
            WHERE tenant_id = :tenant AND id = :id
           """)
    Mono<PaymentEntity> findByTenantAndId(String tenant, Long id);
}
