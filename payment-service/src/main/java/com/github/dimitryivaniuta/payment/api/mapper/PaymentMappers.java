package com.github.dimitryivaniuta.payment.api.mapper;

import com.github.dimitryivaniuta.payment.api.dto.*;
import com.github.dimitryivaniuta.payment.domain.model.CaptureEntity;
import com.github.dimitryivaniuta.payment.domain.model.PaymentEntity;
import com.github.dimitryivaniuta.payment.domain.model.RefundEntity;

public final class PaymentMappers {
    private PaymentMappers() {}

    public static PaymentView toView(PaymentEntity e) {
        return PaymentView.builder()
                .id(e.getId())
                .tenantId(e.getTenantId())
                .sagaId(e.getSagaId())
                .orderId(e.getOrderId())
                .userId(e.getUserId())
                .status(e.getStatus())
                .money(MoneyDto.of(e.getAmountMinor(), e.getCurrencyCode()))
                .psp(e.getPsp())
                .pspRef(e.getPspRef())
                .failureCode(e.getFailureCode())
                .failureReason(e.getFailureReason())
                .nextAction(e.getNextActionJson() == null ? null
                        : NextActionDto.builder().type("PROVIDER_SPECIFIC").data(null).build())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    public static CaptureView toView(CaptureEntity c) {
        return CaptureView.builder()
                .id(c.getId())
                .paymentId(c.getPaymentId())
                .money(MoneyDto.of(c.getAmountMinor(), c.getCurrencyCode()))
                .status(c.getStatus())
                .psp(c.getPsp())
                .pspCaptureRef(c.getPspCaptureRef())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    public static RefundView toView(RefundEntity r) {
        return RefundView.builder()
                .id(r.getId())
                .paymentId(r.getPaymentId())
                .money(MoneyDto.of(r.getAmountMinor(), r.getCurrencyCode()))
                .status(r.getStatus())
                .psp(r.getPsp())
                .pspRefundRef(r.getPspRefundRef())
                .reasonCode(r.getReasonCode())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
