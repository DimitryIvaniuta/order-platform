package com.github.dimitryivaniuta.payment.provider.adyen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.github.dimitryivaniuta.payment.config.PaymentProviderProperties;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Maps Adyen webhook notification items and verifies HMAC signatures.
 *
 * Supports classic JSON format:
 * {
 *   "notificationItems": [
 *     { "NotificationRequestItem": { ... } }
 *   ]
 * }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdyenWebhookMapper {

    private final ObjectMapper om;
    private final PaymentProviderProperties props;

  /* ===========================
     Public API
     =========================== */

    /** Parse the raw JSON payload into a typed envelope. */
    public NotificationEnvelope parse(String json) throws Exception {
        return om.readValue(json, NotificationEnvelope.class);
    }

    /** Verify HMAC signature for a single item (returns false if key missing or invalid). */
    public boolean verifyHmac(NotificationItem item) {
        try {
            String key = props.getAdyen().getHmacKey();
            if (key == null || key.isBlank()) return false;

            byte[] hmacKeyBytes = decodeKey(key);
            String data = hmacDataToSign(item);
            String expected = item.getAdditionalData() == null ? null :
                    (String) item.getAdditionalData().getOrDefault("hmacSignature", null);
            if (expected == null) return false;

            String actual = hmacBase64(hmacKeyBytes, data);
            boolean ok = constantTimeEquals(expected, actual);
            if (!ok) log.warn("Adyen HMAC mismatch for pspRef={} expected={} actual={}", item.getPspReference(), expected, actual);
            return ok;
        } catch (Exception e) {
            log.warn("HMAC verification failed", e);
            return false;
        }
    }

    /** Map Adyen item to a normalized internal event (no DB access here; pure mapping). */
    public NormalizedEvent toNormalizedEvent(NotificationItem item) {
        String code = item.getEventCode();
        String pspRef = item.getPspReference();
        String origRef = item.getOriginalReference();
        String merchantRef = item.getMerchantReference();
        long amountMinor = item.getAmount() == null ? 0L : item.getAmount().getValue();
        String currency = item.getAmount() == null ? null : item.getAmount().getCurrency();
        boolean success = "true".equalsIgnoreCase(item.getSuccess());

        return switch (code == null ? "" : code.toUpperCase()) {
            case "AUTHORISATION" -> new NormalizedEvent.Authorized(pspRef, merchantRef, amountMinor, currency, success);
            case "CAPTURE" -> new NormalizedEvent.Captured(pspRef, origRef, merchantRef, amountMinor, currency, success);
            case "REFUND" -> new NormalizedEvent.Refunded(pspRef, origRef, merchantRef, amountMinor, currency, success);
            case "CANCEL_OR_REFUND", "REFUND_FAILED" -> new NormalizedEvent.RefundFailed(pspRef, origRef, merchantRef, amountMinor, currency);
            case "CHARGEBACK" -> new NormalizedEvent.DisputeOpened(pspRef, merchantRef, amountMinor, currency);
            case "CHARGEBACK_REVERSED" -> new NormalizedEvent.DisputeClosed(pspRef, merchantRef, true);
            case "NOTIFICATION_OF_CHARGEBACK" -> new NormalizedEvent.DisputePending(pspRef, merchantRef);
            default -> new NormalizedEvent.Unknown(code, pspRef, merchantRef);
        };
    }

  /* ===========================
     Types
     =========================== */

    @Value
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NotificationEnvelope {
        NotificationItemWrapper[] notificationItems;
    }

    @Value
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NotificationItemWrapper {
        NotificationItem NotificationRequestItem;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NotificationItem {
        private String eventCode;
        private String success;
        private String pspReference;
        private String originalReference;
        private String merchantAccountCode;
        private String merchantReference;
        private Amount amount;
        private Map<String,Object> additionalData;

        public String getEventCode() { return eventCode; }
        public String getSuccess() { return success; }
        public String getPspReference() { return pspReference; }
        public String getOriginalReference() { return originalReference; }
        public String getMerchantAccountCode() { return merchantAccountCode; }
        public String getMerchantReference() { return merchantReference; }
        public Amount getAmount() { return amount; }
        public Map<String, Object> getAdditionalData() { return additionalData; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Amount {
        private String currency;
        private Long value;
        public String getCurrency() { return currency; }
        public Long getValue() { return value; }
    }

    /** Normalized events your webhook handler can route to the right service. */
    public sealed interface NormalizedEvent permits
            NormalizedEvent.Authorized,
            NormalizedEvent.Captured,
            NormalizedEvent.Refunded,
            NormalizedEvent.RefundFailed,
            NormalizedEvent.DisputeOpened,
            NormalizedEvent.DisputeClosed,
            NormalizedEvent.DisputePending,
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
        @Value class RefundFailed implements NormalizedEvent {
            String pspRef; String originalRef; String merchantRef; long amountMinor; String currency;
        }
        @Value class DisputeOpened implements NormalizedEvent {
            String pspRef; String merchantRef; long amountMinor; String currency;
        }
        @Value class DisputeClosed implements NormalizedEvent {
            String pspRef; String merchantRef; boolean reversed;
        }
        @Value class DisputePending implements NormalizedEvent {
            String pspRef; String merchantRef;
        }
        @Value class Unknown implements NormalizedEvent {
            String eventCode; String pspRef; String merchantRef;
        }
    }

  /* ===========================
     HMAC helpers (classic Adyen recipe)
     =========================== */

    private static String hmacDataToSign(NotificationItem item) {
        // order matters; escape ':' and '\' per Adyen rules
        return escape(item.getPspReference()) + ":" +
                escape(nullToEmpty(item.getOriginalReference())) + ":" +
                escape(nullToEmpty(item.getMerchantAccountCode())) + ":" +
                escape(nullToEmpty(item.getMerchantReference())) + ":" +
                escape(item.getAmount() == null ? "" : String.valueOf(item.getAmount().getValue())) + ":" +
                escape(item.getAmount() == null ? "" : nullToEmpty(item.getAmount().getCurrency())) + ":" +
                escape(nullToEmpty(item.getEventCode())) + ":" +
                escape(nullToEmpty(item.getSuccess()));
    }

    private static String escape(String s) {
        String x = s == null ? "" : s;
        // First escape backslash, then colon
        return x.replace("\\", "\\\\").replace(":", "\\:");
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String hmacBase64(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(raw);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }

    private static byte[] decodeKey(String key) {
        // Support base64 or hex HMAC secrets
        String k = key.trim();
        try {
            if (k.matches("^[A-Fa-f0-9]+$") && (k.length() % 2 == 0)) {
                int len = k.length();
                byte[] out = new byte[len / 2];
                for (int i = 0; i < len; i += 2) {
                    out[i/2] = (byte) Integer.parseInt(k.substring(i, i+2), 16);
                }
                return out;
            }
            return Base64.getDecoder().decode(k);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid HMAC key format (expect HEX or Base64)", e);
        }
    }
}
