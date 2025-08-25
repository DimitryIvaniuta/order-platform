package com.github.dimitryivaniuta.payment.saga.events;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** Sealed hierarchy of Payment events emitted to Kafka. */
public sealed interface PaymentEvents permits
        PaymentEvents.Authorized,
        PaymentEvents.AuthFailed,
        PaymentEvents.Captured,
        PaymentEvents.Refunded,
        PaymentEvents.NextActionRequired {

    String type();

    @Jacksonized @Builder
    record Authorized(
            UUID sagaId,
            String tenantId,
            Long orderId,
            Long paymentId,
            Long amountMinor,
            String currencyCode,
            String psp,
            String pspRef,
            Instant ts
    ) implements PaymentEvents {
        public String type() { return "PAYMENT_AUTHORIZED"; }
    }

    @Jacksonized @Builder
    record AuthFailed(
            UUID sagaId,
            String tenantId,
            Long orderId,
            Long paymentId,
            String failureCode,
            String failureReason,
            Instant ts
    ) implements PaymentEvents {
        public String type() { return "PAYMENT_AUTH_FAILED"; }
    }

    @Jacksonized @Builder
    record Captured(
            UUID sagaId,
            String tenantId,
            Long orderId,
            Long paymentId,
            Long captureId,
            Long amountMinor,
            String currencyCode,
            String psp,
            String pspCaptureRef,
            Instant ts
    ) implements PaymentEvents {
        public String type() { return "PAYMENT_CAPTURED"; }
    }

    @Jacksonized @Builder
    record Refunded(
            UUID sagaId,
            String tenantId,
            Long orderId,
            Long paymentId,
            Long refundId,
            Long amountMinor,
            String currencyCode,
            String psp,
            String pspRefundRef,
            String reasonCode,
            Instant ts
    ) implements PaymentEvents {
        public String type() { return "PAYMENT_REFUNDED"; }
    }

    @Jacksonized @Builder
    record NextActionRequired(
            UUID sagaId,
            String tenantId,
            Long orderId,
            Long paymentId,
            String actionType,           // e.g., "REDIRECT", "THREEDS_CHALLENGE"
            java.util.Map<String,Object> data,
            Instant ts
    ) implements PaymentEvents {
        public String type() { return "PAYMENT_AUTH_NEXT_ACTION_REQUIRED"; }
    }
}
