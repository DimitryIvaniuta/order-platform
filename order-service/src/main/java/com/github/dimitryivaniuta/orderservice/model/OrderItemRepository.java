package com.github.dimitryivaniuta.orderservice.model;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderItemRepository extends ReactiveCrudRepository<OrderItemEntity, Long> {

    Flux<OrderItemEntity> findAllByOrderIdAndTenantId(Long orderId, String tenantId);

    Mono<Long> deleteByIdAndOrderIdAndTenantId(Long id, Long orderId, String tenantId);

    @Query("""
        SELECT COALESCE(SUM(line_total), 0) 
        FROM order_items 
        WHERE order_id = :orderId AND tenant_id = :tenantId
    """)
    Mono<java.math.BigDecimal> sumTotals(Long orderId, String tenantId);

    Flux<OrderItemEntity> findByTenantIdAndOrderId(String tenantId, Long orderId);

    Mono<OrderItemEntity> findByIdAndTenantId(Long id, String tenantId);
}
