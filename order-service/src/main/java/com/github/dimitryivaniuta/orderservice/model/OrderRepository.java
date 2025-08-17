package com.github.dimitryivaniuta.orderservice.model;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface OrderRepository extends ReactiveCrudRepository<OrderEntity, Long> {
    Mono<OrderEntity> findByIdAndTenantId(Long id, String tenantId);
    Mono<Boolean> existsByIdAndTenantId(Long id, String tenantId);
}