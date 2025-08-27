package com.github.dimitryivaniuta.payment.provider.stripe;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.github.dimitryivaniuta.payment.config.PaymentProviderProperties;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stripe webhook signature verification + event normalization.
 *
 * Verifies "Stripe-Signature" header using endpointSecret and default tolerance (5m).
 * Maps a subset of event types to normalized events used by your services.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeWebhookMapper {

    private final ObjectMapper om;
    private final PaymentProviderProperties props;

  /* ===========================
     Public API
     =========================== */

    /** Verify the Stripe signature header for the given raw payload. */
    public boolean verify(String rawPayload, String stripeSignatureHeader) {
        try {
            var secret = props.getStripe().getWebhook().getEndpointSecret();
            if (secret == null || secret.isBlank()) return false;

            Map<String, String> parts = parseSignatureHeader(stripeSignatureHeader);
            String timestamp = parts.get("t");
            String v1 = parts.get("v1");
            if (timestamp == null || v1 == null) return false;

            Duration tol = props.getStripe().getWebhook().getTolerance();
            long ts = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - ts) > tol.getSeconds()) return false;

            String signedPayload = timestamp + "." + rawPayload;
            String computed = hmacSha256Hex(secret, signedPayload);
            return constantTimeEquals(v1, computed);
        } catch (Exception e) {
            log.warn("Stripe webhook verification failed", e);
            return false;
        }
    }

    /** Parse the JSON payload to a typed Stripe event. */
    public StripeEvent parse(String json) throws Exception {
        return om.readValue(json, StripeEvent.class);
    }

    /** Convert a Stripe event into a normalized internal event. */
    public NormalizedEvent toNormalized(StripeEvent ev) {
        String type = (ev.type == null) ? "" : ev.type;
        JsonNode obj = ev.data == null ? null : ev.data.object;

        return switch (type) {
            // Authorization (manual capture)
            case "payment_intent.amount_capturable_updated" -> {
                String pi = text(obj, "id");
                long capturable = longVal(obj, "amount_capturable");
                String currency = text(obj, "currency");
                yield new NormalizedEvent.Authorized(pi, pi, capturable, currency, true);
            }
            // Capture success (manual capture leads to a captured charge)
            case "charge.captured", "charge.succeeded" -> {
                String charge = text(obj, "id");
                long amount = longVal(obj, "amount");
                String currency = text(obj, "currency");
                String pi = text(obj, "payment_intent");
                yield new NormalizedEvent.Captured(charge, pi, pi, amount, currency, true);
            }
            // Refunds
            case "charge.refunded" -> {
                String charge = text(obj, "id");
                long amount = longVal(obj, "amount_refunded");
                String currency = text(obj, "currency");
                String pi = text(obj, "payment_intent");
                yield new NormalizedEvent.Refunded("rfnd_"+charge, charge, pi, amount, currency, true);
            }
            case "refund.updated", "refund.succeeded", "refund.created" -> {
                String refund = text(obj, "id");
                String charge = text(obj, "charge");
                long amount = longVal(obj, "amount");
                String currency = text(obj, "currency");
                String pi = text(obj, "payment_intent");
                yield new NormalizedEvent.Refunded(refund, charge, pi, amount, currency, true);
            }
            // Disputes
            case "charge.dispute.created" -> {
                String dispute = text(obj, "id");
                String charge = text(obj, "charge");
                long amount = longVal(obj, "amount");
                String currency = text(obj, "currency");
                yield new NormalizedEvent.DisputeOpened(dispute, charge, amount, currency);
            }
            case "charge.dispute.closed" -> {
                String dispute = text(obj, "id");
                String charge = text(obj, "charge");
                boolean won = "won".equalsIgnoreCase(text(obj, "status"));
                yield new NormalizedEvent.DisputeClosed(dispute, charge, won);
            }
            default -> new NormalizedEvent.Unknown(type, null, null);
        };
    }

  /* ===========================
     Types
     =========================== */

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StripeEvent {
        public String id;
        public String type;
        public Data data;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Data {
            public JsonNode object; // heterogeneous: payment_intent, charge, refund, dispute
        }
    }

    /** Normalized events (same style as Adyen mapper). */
    public sealed interface NormalizedEvent permits
            NormalizedEvent.Authorized,
            NormalizedEvent.Captured,
            NormalizedEvent.Refunded,
            NormalizedEvent.DisputeOpened,
            NormalizedEvent.DisputeClosed,
            NormalizedEvent.Unknown {

        @Value class Authorized implements NormalizedEvent {
            String pspRef; String merchantRef; long amountMinor; String currency; boolean success;
        }
        @Value class Captured implements NormalizedEvent {
            String pspRef; String originalAuthRef; String merchantRef; long amountMinor; String currency; boolean success;
        }
        @Value class Refunded implements NormalizedEvent {
            String pspRef; String originalCaptureOrAuthRef; String merchantRef; long amountMinor; String currency; boolean success;
        }
        @Value class DisputeOpened implements NormalizedEvent {
            String pspRef; String merchantRef; long amountMinor; String currency;
        }
        @Value class DisputeClosed implements NormalizedEvent {
            String pspRef; String merchantRef; boolean won;
        }
        @Value class Unknown implements NormalizedEvent {
            String eventCode; String pspRef; String merchantRef;
        }
    }

  /* ===========================
     Helpers
     =========================== */

    private static Map<String, String> parseSignatureHeader(String header) {
        Map<String, String> map = new HashMap<>();
        if (header == null) return map;
        for (String part : header.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    private static String hmacSha256Hex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return null;
        return node.get(field).asText(null);
    }

    private static long longVal(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return 0L;
        return node.get(field).asLong(0L);
    }
}
