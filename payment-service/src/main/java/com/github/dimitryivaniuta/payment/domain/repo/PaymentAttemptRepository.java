package com.github.dimitryivaniuta.payment.domain.repo;

import com.github.dimitryivaniuta.payment.domain.model.PaymentAttemptEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface PaymentAttemptRepository extends ReactiveCrudRepository<PaymentAttemptEntity, Long> {
}

