package com.github.dimitryivaniuta.payment.outbox;

import java.time.LocalDate;

/** Composite identifier for the partitioned outbox row. */
public record OutboxKey(Long id, LocalDate createdOn) {}
