package com.github.dimitryivaniuta.payment.api.dto;

import java.time.Instant;

import com.github.dimitryivaniuta.payment.domain.model.RefundStatus;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** Result view for a refund operation. */
@Jacksonized
@Builder
public record RefundView(
        Long id,
        Long paymentId,
        MoneyDto money,
        RefundStatus status,
        String psp,
        String pspRefundRef,
        String reasonCode,
        Instant createdAt,
        Instant updatedAt
) {}
