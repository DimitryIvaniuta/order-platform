package com.github.dimitryivaniuta.payment.domain.model;


/** Mandate (e.g., SEPA DD) status. */
public enum MandateStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}