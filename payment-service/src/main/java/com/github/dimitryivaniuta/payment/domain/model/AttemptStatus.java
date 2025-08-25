package com.github.dimitryivaniuta.payment.domain.model;

/** Status for an individual authorization attempt. */
public enum AttemptStatus {
    FAILED,
    PENDING,
    REQUIRES_ACTION,
    SUCCEEDED
}