package com.github.dimitryivaniuta.payment.provider.stripe;

import com.github.dimitryivaniuta.payment.config.PaymentProviderProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripePaymentProvider {

    /** Same Result shape used by your other providers. */
    public record Result(boolean authorized, String externalRef, String failureCode, String failureReason) {}

    private final StripeClient client;
    private final PaymentProviderProperties props;

  /* ===========================
     AUTHORIZATION (manual capture)
     =========================== */

    /**
     * Authorize by creating a PaymentIntent (manual capture).
     * @param amountMinor   minor units
     * @param currency      ISO currency (e.g., "USD")
     * @param reference     human-readable description / correlation
     * @param paymentMethodId Stripe payment method id (e.g., pm_xxx). Required when confirm=true
     * @param idempotencyKey caller-provided idempotency key (e.g., sagaId / paymentId)
     */
    public Result authorize(long amountMinor,
                            String currency,
                            String reference,
                            String paymentMethodId,
                            String idempotencyKey) {
        try {
            var pi = client.createPaymentIntentManualCapture(
                    amountMinor, currency, reference, paymentMethodId, true, idempotencyKey
            ).block();

            if (pi == null) return fail("NO_RESPONSE", "No response from Stripe");

            // Treat "requires_capture" and "processing" as authorized; "succeeded" can happen on auto-capture flows.
            String st = safe(pi.getStatus());
            if (st.equals("requires_capture") || st.equals("processing") || st.equals("succeeded")) {
                return ok(pi.getId());
            }
            if (st.equals("requires_action")) {
                return fail("REQUIRES_ACTION", "Further customer action required (3DS/SCA)");
            }
            // requires_payment_method, requires_confirmation, canceled, etc.
            return fail(st.toUpperCase(), "Stripe PaymentIntent status: " + st);
        } catch (Exception e) {
            log.warn("Stripe authorize failed", e);
            return fail("EXCEPTION", e.getMessage());
        }
    }

  /* ===========================
     CAPTURE
     =========================== */

    public Result capture(long amountMinor,
                          String currency,
                          String paymentIntentId,
                          String idempotencyKey) {
        try {
            var pi = client.capturePaymentIntent(paymentIntentId, amountMinor, idempotencyKey).block();
            if (pi == null) return fail("NO_RESPONSE", "No response from Stripe");
            String st = safe(pi.getStatus());
            // On success we get PI with charges; the capture ref we return is the charge id.
            String chargeId = (pi.getLatestCharge() != null) ? pi.getLatestCharge()
                    : (pi.getCharges() != null && pi.getCharges().data != null && !pi.getCharges().data.isEmpty()
                    ? pi.getCharges().data.getFirst().getId() : null);

            if (st.equals("succeeded") || st.equals("processing")) {
                return ok(chargeId != null ? chargeId : paymentIntentId);
            }
            return fail(st.toUpperCase(), "Stripe capture status: " + st);
        } catch (Exception e) {
            log.warn("Stripe capture failed", e);
            return fail("EXCEPTION", e.getMessage());
        }
    }

  /* ===========================
     REFUND
     =========================== */

    public Result refund(long amountMinor,
                         String currency,
                         String chargeId,
                         String idempotencyKey) {
        try {
            var r = client.createRefund(chargeId, amountMinor, idempotencyKey).block();
            if (r == null) return fail("NO_RESPONSE", "No response from Stripe");
            String st = safe(r.getStatus());
            if (st.equals("succeeded") || st.equals("pending")) {
                return ok(r.getId());
            }
            return fail(st.toUpperCase(), "Stripe refund status: " + st);
        } catch (Exception e) {
            log.warn("Stripe refund failed", e);
            return fail("EXCEPTION", e.getMessage());
        }
    }

  /* ===========================
     Helpers
     =========================== */

    private static Result ok(String ref) { return new Result(true, ref, null, null); }
    private static Result fail(String code, String reason) { return new Result(false, null, code, reason); }
    private static String safe(String s) { return s == null ? "" : s; }
}
