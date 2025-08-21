package com.github.dimitryivaniuta.orderservice.model;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface DiscountCodeRepository extends ReactiveCrudRepository<DiscountCodeEntity, String> {
}