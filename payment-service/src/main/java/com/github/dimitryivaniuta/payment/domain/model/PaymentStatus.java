package com.github.dimitryivaniuta.payment.domain.model;

/** Lifecycle of a payment (provider-agnostic). */
public enum PaymentStatus {
    AUTHORIZED,
    AUTHORIZING,
    CAPTURED,
    CAPTURING,
    CANCELLED,
    FAILED,
    INITIATED,
    REQUIRES_ACTION,
    SETTLED
}