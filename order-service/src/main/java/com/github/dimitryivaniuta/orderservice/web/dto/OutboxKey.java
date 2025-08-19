package com.github.dimitryivaniuta.orderservice.web.dto;

import java.time.LocalDate;

/** Composite PK for partitioned table. */
public record OutboxKey(Long id, LocalDate createdOn) {}