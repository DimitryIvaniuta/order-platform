package com.github.dimitryivaniuta.payment.saga.events;

import java.util.UUID;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** Command from Order -> Payment to request an authorization. */
@Jacksonized
@Builder
public record PaymentAuthorizeRequested(
        String type,       // "PAYMENT_AUTHORIZE_REQUESTED"
        UUID sagaId,
        String tenantId,
        Long orderId,
        Long amountMinor,
        String currencyCode,
        UUID userId
) {}
