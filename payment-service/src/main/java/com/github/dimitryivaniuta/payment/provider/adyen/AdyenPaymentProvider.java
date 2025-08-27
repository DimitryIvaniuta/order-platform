package com.github.dimitryivaniuta.payment.provider.adyen;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Provider adapter with a simple, synchronous façade API to match your service usage.
 * Internally calls the reactive AdyenClient and blocks at the boundary via .blockOptional()
 * ONLY for these minimal operations—so the rest of your pipeline stays reactive.
 *
 * If you prefer fully-reactive end-to-end, expose Mono<Result> variants and update services accordingly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdyenPaymentProvider {

    public record Result(boolean authorized, String externalRef, String failureCode, String failureReason) {}

    private final AdyenClient adyen;

  /* ===========================
     AUTHORIZATION
     =========================== */

    /**
     * Authorize a payment. The payment method should be a tokenized detail map
     * (e.g., { "type": "scheme", "storedPaymentMethodId": "...." }).
     *
     * @param amountMinor minor units
     * @param currency ISO code
     * @param reference merchant reference you can use to correlate (e.g., "tenant:order:paymentId")
     * @param paymentMethod tokenized method map
     * @param idempotencyKey caller idempotency key (saga/payment id)
     */
    public Result authorize(long amountMinor,
                            String currency,
                            String reference,
                            Map<String,Object> paymentMethod,
                            String idempotencyKey) {

        var req = new AdyenClient.PaymentRequest(
                AdyenClient.amount(currency, amountMinor),
                reference,
                adyen.props().getMerchantAccount(),
                paymentMethod,
                adyen.props().getDefaultReturnUrl(),
                Boolean.TRUE,
                AdyenClient.map("allow3DS2", true) // hint; Adyen may switch to action flow
        );

        var respOpt = adyen.payments(req, idempotencyKey).blockOptional();
        if (respOpt.isEmpty()) {
            return fail("NO_RESPONSE", "No response from Adyen");
        }
        var resp = respOpt.get();
        var code = safe(resp.getResultCode());
        if ("Authorised".equalsIgnoreCase(code)) {
            return ok(resp.getPspReference());
        }
        if ("Pending".equalsIgnoreCase(code) || "Received".equalsIgnoreCase(code)) {
            // Treat as success to move forward, or surface REQUIRES_ACTION in your domain if you prefer
            return ok(resp.getPspReference());
        }
        return fail(code == null ? "REFUSED" : code.toUpperCase(), safe(resp.getRefusalReason()));
    }

  /* ===========================
     CAPTURE
     =========================== */

    public Result capture(long amountMinor,
                          String currency,
                          String authPspReference,
                          String idempotencyKey) {
        var req = new AdyenClient.CaptureRequest(
                AdyenClient.amount(currency, amountMinor),
                adyen.props().getMerchantAccount(),
                "cap-" + UUID.randomUUID()
        );
        var respOpt = adyen.captures(authPspReference, req, idempotencyKey).blockOptional();
        if (respOpt.isEmpty()) {
            return fail("NO_RESPONSE", "No response from Adyen");
        }
        var resp = respOpt.get();
        // Adyen returns "received" and processes async; consider webhooks for final state
        return ok(resp.getPspReference());
    }

  /* ===========================
     REFUND
     =========================== */

    public Result refund(long amountMinor,
                         String currency,
                         String captureOrAuthPspReference,
                         String idempotencyKey) {
        var req = new AdyenClient.RefundRequest(
                AdyenClient.amount(currency, amountMinor),
                adyen.props().getMerchantAccount(),
                "ref-" + UUID.randomUUID()
        );
        var respOpt = adyen.refunds(captureOrAuthPspReference, req, idempotencyKey).blockOptional();
        if (respOpt.isEmpty()) {
            return fail("NO_RESPONSE", "No response from Adyen");
        }
        var resp = respOpt.get();
        return ok(resp.getPspReference());
    }

  /* ===========================
     Helpers
     =========================== */

    private static Result ok(String ref) {
        return new Result(true, ref, null, null);
    }

    private static Result fail(String code, String reason) {
        return new Result(false, null, code, reason);
    }

    private static String safe(String s) {
        return s;
    }
}
