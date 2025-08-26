package com.github.dimitryivaniuta.payment.domain.repo;

import com.github.dimitryivaniuta.payment.domain.model.LedgerEntryEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface LedgerEntryRepository extends ReactiveCrudRepository<LedgerEntryEntity, Long> {
}
