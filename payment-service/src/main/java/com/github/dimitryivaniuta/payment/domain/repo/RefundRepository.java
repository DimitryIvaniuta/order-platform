package com.github.dimitryivaniuta.payment.domain.repo;

import com.github.dimitryivaniuta.payment.domain.model.RefundEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface RefundRepository extends ReactiveCrudRepository<RefundEntity, Long> {
}
