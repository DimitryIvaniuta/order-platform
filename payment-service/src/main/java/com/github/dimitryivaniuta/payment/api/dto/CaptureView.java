package com.github.dimitryivaniuta.payment.api.dto;

import java.time.Instant;

import com.github.dimitryivaniuta.payment.domain.model.CaptureStatus;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** Result view for a capture operation. */
@Jacksonized
@Builder
public record CaptureView(
        Long id,
        Long paymentId,
        MoneyDto money,
        CaptureStatus status,
        String psp,
        String pspCaptureRef,
        Instant createdAt,
        Instant updatedAt
) {}
