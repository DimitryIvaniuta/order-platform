package com.github.dimitryivaniuta.payment.domain.model;

/** Dispute/chargeback progression stage. */
public enum DisputeStage {
    ARBITRATION,
    CANCELLED,
    CLOSED,
    EVIDENCE_SUBMITTED,
    LOST,
    OPENED,
    WON
}