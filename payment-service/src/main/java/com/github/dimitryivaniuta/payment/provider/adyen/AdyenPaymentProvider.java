package com.github.dimitryivaniuta.payment.provider.adyen;

import com.github.dimitryivaniuta.payment.api.dto.PaymentAuthResponse;
import com.github.dimitryivaniuta.payment.api.dto.PaymentCaptureResponse;
import com.github.dimitryivaniuta.payment.api.dto.PaymentRequest;
import com.github.dimitryivaniuta.payment.api.dto.RefundRequest;
import com.github.dimitryivaniuta.payment.api.dto.RefundResponse;
import com.github.dimitryivaniuta.payment.provider.adyen.dto.PaymentCaptureRequest;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Thin adapter around {@link AdyenClient}.
 * - Builds typed requests from simple primitives.
 * - Interprets Adyen result codes into a compact {@link Result}.
 * - Provides sync facades (blocking at the edge) and reactive alternatives.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdyenPaymentProvider {

    @Value
    public static class Result {
        boolean authorized;
        String externalRef;     // PSP ref
        String failureCode;     // e.g., "REFUSED"
        String failureReason;   // provider message
        boolean requiresAction; // SCA / redirect flow
    }

    private final AdyenClient adyen;

    /* ==========================================================
       AUTHORIZATION
       ========================================================== */

    /**
     * Synchronous authorization facade.
     * @param amountMinor  minor units (e.g., cents)
     * @param currency     ISO 4217 code (e.g., "USD")
     * @param reference    your merchant reference (idempotent in your domain)
     * @param paymentMethod tokenized payment method map (Adyen format)
     * @param idempotencyKey Adyen idempotency key
     */
    public Result authorize(long amountMinor,
                            String currency,
                            String reference,
                            Map<String, Object> paymentMethod,
                            String idempotencyKey) {

        PaymentRequest req = PaymentRequest.builder()
                .amount(new PaymentRequest.Amount(currency, amountMinor))
                .reference(reference)
                .merchantAccount(adyen.props().getMerchantAccount())
                .paymentMethod(paymentMethod)
                .returnUrl(adyen.props().getDefaultReturnUrl())
                .storePaymentMethod(Boolean.TRUE)
                .additionalData(Map.of("allow3DS2", true))
                .build();

        PaymentAuthResponse resp = adyen.payments(req, idempotencyKey).block();
        return toAuthResult(resp);
    }

    /** Reactive variant (no blocking). */
    public Mono<Result> authorizeRx(long amountMinor,
                                    String currency,
                                    String reference,
                                    Map<String, Object> paymentMethod,
                                    String idempotencyKey) {
        PaymentRequest req = PaymentRequest.builder()
                .amount(new PaymentRequest.Amount(currency, amountMinor))
                .reference(reference)
                .merchantAccount(adyen.props().getMerchantAccount())
                .paymentMethod(paymentMethod)
                .returnUrl(adyen.props().getDefaultReturnUrl())
                .storePaymentMethod(Boolean.TRUE)
                .additionalData(Map.of("allow3DS2", true))
                .build();

        return adyen.payments(req, idempotencyKey).map(this::toAuthResult);
    }

    private Result toAuthResult(PaymentAuthResponse resp) {
        if (resp == null) {
            log.warn("Adyen authorize: no response");
            return new Result(false, null, "NO_RESPONSE", "No response from Adyen", false);
        }
        final String code = nz(resp.resultCode());
        final String psp  = nz(resp.pspReference());
        final String refusal = nz(resp.refusalReason());
        final boolean hasAction = resp.action() != null && !resp.action().isEmpty();

        // Typical codes: "Authorised", "Refused", "Pending", "Received"
        if ("Authorised".equalsIgnoreCase(code)) {
            return new Result(true, psp, null, null, hasAction);
        }
        if ("Pending".equalsIgnoreCase(code) || "Received".equalsIgnoreCase(code)) {
            // If you want to force SCA step handling here, set authorized=false and requiresAction=true when action!=null
            return new Result(true, psp, null, null, hasAction);
        }
        return new Result(false, null, code.isBlank() ? "REFUSED" : code.toUpperCase(), refusal, hasAction);
    }

    /* ==========================================================
       CAPTURE
       ========================================================== */

    /**
     * Synchronous capture facade.
     * @param amountMinor amount to capture in minor units
     * @param currency ISO 4217
     * @param authPspReference original authorization PSP reference
     * @param idempotencyKey Adyen idempotency key for capture
     */
    public Result capture(long amountMinor,
                          String currency,
                          String authPspReference,
                          String idempotencyKey) {

        PaymentCaptureRequest req = PaymentCaptureRequest.builder()
                .amount(new PaymentCaptureRequest.Amount(currency, amountMinor))
                .merchantAccount(adyen.props().getMerchantAccount())
                .reference("cap-" + UUID.randomUUID())
                .build();

        PaymentCaptureResponse resp = adyen.captures(authPspReference, req, idempotencyKey).block();
        return toCaptureOrRefundResult(resp == null ? null : resp.pspReference());
    }

    /** Reactive variant (no blocking). */
    public Mono<Result> captureRx(long amountMinor,
                                  String currency,
                                  String authPspReference,
                                  String idempotencyKey) {
        PaymentCaptureRequest req = PaymentCaptureRequest.builder()
                .amount(new PaymentCaptureRequest.Amount(currency, amountMinor))
                .merchantAccount(adyen.props().getMerchantAccount())
                .reference("cap-" + UUID.randomUUID())
                .build();

        return adyen.captures(authPspReference, req, idempotencyKey)
                .map(r -> toCaptureOrRefundResult(r == null ? null : r.pspReference()));
    }

    /* ==========================================================
       REFUND
       ========================================================== */

    /**
     * Synchronous refund facade.
     * @param amountMinor  refund amount in minor units
     * @param currency     ISO 4217
     * @param captureOrAuthPspReference PSP reference of capture (preferred) or auth
     * @param idempotencyKey Adyen idempotency key for refund
     */
    public Result refund(long amountMinor,
                         String currency,
                         String captureOrAuthPspReference,
                         String idempotencyKey) {

        RefundRequest req = RefundRequest.builder()
                .amount(new RefundRequest.Amount(currency, amountMinor))
                .merchantAccount(adyen.props().getMerchantAccount())
                .reference("ref-" + UUID.randomUUID())
                .build();

        RefundResponse resp = adyen.refunds(captureOrAuthPspReference, req, idempotencyKey).block();
        return toCaptureOrRefundResult(resp == null ? null : resp.pspReference());
    }

    /** Reactive variant (no blocking). */
    public Mono<Result> refundRx(long amountMinor,
                                 String currency,
                                 String captureOrAuthPspReference,
                                 String idempotencyKey) {
        RefundRequest req = RefundRequest.builder()
                .amount(new RefundRequest.Amount(currency, amountMinor))
                .merchantAccount(adyen.props().getMerchantAccount())
                .reference("ref-" + UUID.randomUUID())
                .build();

        return adyen.refunds(captureOrAuthPspReference, req, idempotencyKey)
                .map(r -> toCaptureOrRefundResult(r == null ? null : r.pspReference()));
    }

    /* ==========================================================
       Helpers
       ========================================================== */

    private Result toCaptureOrRefundResult(String pspReference) {
        if (pspReference == null || pspReference.isBlank()) {
            return new Result(false, null, "NO_RESPONSE", "No PSP reference returned", false);
        }
        // Adyen capture/refund are often async -> treat as accepted; final state via webhook
        return new Result(true, pspReference, null, null, false);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
