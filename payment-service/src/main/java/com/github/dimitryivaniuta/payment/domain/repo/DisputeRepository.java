package com.github.dimitryivaniuta.payment.domain.repo;

import com.github.dimitryivaniuta.payment.domain.model.DisputeEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface DisputeRepository extends ReactiveCrudRepository<DisputeEntity, Long> {
}

