package com.github.dimitryivaniuta.payment.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.github.dimitryivaniuta.payment.domain.model.PaymentStatus;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** Public view of a Payment aggregate. */
@Jacksonized
@Builder
public record PaymentView(
        Long id,
        String tenantId,
        UUID sagaId,
        Long orderId,
        UUID userId,
        PaymentStatus status,
        MoneyDto money,
        String psp,
        String pspRef,
        String failureCode,
        String failureReason,
        NextActionDto nextAction,
        Instant createdAt,
        Instant updatedAt
) {}
