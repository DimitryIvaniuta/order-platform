package com.github.dimitryivaniuta.payment.domain.repo;

import com.github.dimitryivaniuta.payment.domain.model.CaptureEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface CaptureRepository extends ReactiveCrudRepository<CaptureEntity, Long> {
}

