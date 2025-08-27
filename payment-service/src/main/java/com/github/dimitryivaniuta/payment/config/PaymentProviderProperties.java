package com.github.dimitryivaniuta.payment.config;

import jakarta.validation.constraints.Min;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Unified configuration for payment providers.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "provider")
public class PaymentProviderProperties {

    /** Optional common defaults shared across providers. */
    @Valid
    private Common common = new Common();

    /** Fake (stub) provider for development/integration tests. */
    @Valid
    private Fake fake = new Fake();

    /** Adyen Checkout configuration. */
    @Valid
    private Adyen adyen = new Adyen();

    /** Stripe configuration. */
    @Valid
    private Stripe stripe = new Stripe();

  /* ===========================
     Sub-sections
     =========================== */

    @Getter @Setter
    @Validated
    public static class Common {
        /** Default idempotency key prefix (provider-specific can override). */
        private String idempotencyPrefix = "payment-service:";
        /** Shared HTTP timeouts that providers may inherit. */
        @Valid
        private Timeouts timeouts = new Timeouts();
    }

    @Getter @Setter
    @Validated
    public static class Fake {
        private boolean enabled = true;

        /** Artificial latency bounds for reactive simulations. */
        private Duration minLatency = Duration.ofMillis(10);
        private Duration maxLatency = Duration.ofMillis(50);

        /** Upper bound for allowed amount (minor units). */
        @Min(1)
        private long maxAmountMinor = 10_000_000L;

        /** Deterministic risk rule (fail if amount % riskModulo == 0). */
        @Min(2)
        private int riskModulo = 13;

        /**
         * Currency code regex. Keep free-form (don’t validate against itself).
         * Example default: "^[A-Z]{3}$"
         */
        private String currencyPattern = "^[A-Z]{3}$";
    }

    @Getter @Setter
    @ToString
    @Validated
    public static class Adyen {
        private boolean enabled = false;

        /** REST API key (test/live). Keep secret redacted from toString. */
        @ToString.Exclude
        private String apiKey;

        /** Merchant account code (e.g., "OrderPlatform_US"). */
        private String merchantAccount;

        /** Base URL of Checkout API. Test default points to v70. */
        @NotBlank
        private String baseUrl = "https://checkout-test.adyen.com/v70";

        /** Webhook HMAC key (HEX or Base64). */
        @ToString.Exclude
        private String hmacKey;

        /** Override idempotency prefix for Adyen (falls back to provider.common). */
        private String idempotencyPrefix;

        /** Optional default return URL for redirect/3DS flows. */
        private String defaultReturnUrl = "http://localhost:8080/api/payments/adyen/return";

        /** Per-provider timeouts; if null, use provider.common.timeouts. */
        @Valid
        private Timeouts timeouts;

        /** Effective timeouts: provider-specific or common. */
        public Timeouts effectiveTimeouts(Common common) {
            return timeouts != null ? timeouts : common.getTimeouts();
        }

        /** Effective idempotency prefix: provider-specific or common. */
        public String effectiveIdempotencyPrefix(Common common) {
            return (idempotencyPrefix != null && !idempotencyPrefix.isBlank())
                    ? idempotencyPrefix
                    : common.getIdempotencyPrefix();
        }
    }

    @Getter @Setter
    @ToString
    @Validated
    public static class Stripe {
        private boolean enabled = false;

        /** Secret API key, e.g. "sk_test_...". */
        @ToString.Exclude
        private String apiKey;

        /** Optional publishable key (frontend), e.g. "pk_test_...". */
        @ToString.Exclude
        private String publishableKey;

        /** Optional connected / platform account id, e.g. "acct_123...". */
        private String accountId;

        /** Stripe API base (rarely changed). */
        @NotBlank
        private String baseUrl = "https://api.stripe.com";

        /** Optional explicit API version override (e.g., "2023-10-16"). */
        private String apiVersion;

        /** Idempotency-Key prefix sent with requests. */
        private String idempotencyPrefix;

        /** Webhook verification config. */
        @Valid
        private Webhook webhook = new Webhook();

        /** Per-provider timeouts; if null, use provider.common.timeouts. */
        @Valid
        private Timeouts timeouts;

        public Timeouts effectiveTimeouts(Common common) {
            return timeouts != null ? timeouts : common.getTimeouts();
        }

        public String effectiveIdempotencyPrefix(Common common) {
            return (idempotencyPrefix != null && !idempotencyPrefix.isBlank())
                    ? idempotencyPrefix
                    : common.getIdempotencyPrefix();
        }

        @Getter @Setter
        @ToString
        @Validated
        public static class Webhook {
            /** Stripe endpoint secret (e.g., "whsec_..."). */
            @ToString.Exclude
            private String endpointSecret;

            /** Signature age tolerance (default 5 minutes). */
            private Duration tolerance = Duration.ofMinutes(5);
        }
    }

    @Getter @Setter
    @Validated
    public static class Timeouts {
        /** TCP connect timeout. */
        private Duration connect = Duration.ofSeconds(2);
        /** Overall read/response timeout. */
        private Duration read = Duration.ofSeconds(10);
    }

}
