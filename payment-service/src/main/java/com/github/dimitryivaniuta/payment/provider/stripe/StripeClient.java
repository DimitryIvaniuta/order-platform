package com.github.dimitryivaniuta.payment.provider.stripe;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.github.dimitryivaniuta.payment.config.PaymentProviderProperties;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Minimal, typed Stripe API client via WebClient.
 * Uses application/x-www-form-urlencoded as required by Stripe.
 */
@Slf4j
//@Component
@RequiredArgsConstructor
public class StripeClient {

    private final PaymentProviderProperties props;

    private WebClient client() {
        PaymentProviderProperties.Stripe sp = props.getStripe();
        PaymentProviderProperties.Timeouts to = sp.effectiveTimeouts(props.getCommon());
        Duration read = to.getRead();

        HttpClient http = HttpClient.create()
                .responseTimeout(read)
                .compress(true);

        // Convert 4xx/5xx into WebClientResponseException with body captured
        ExchangeFilterFunction errorFilter = ExchangeFilterFunction.ofResponseProcessor(resp -> {
            if (resp.statusCode().is4xxClientError() || resp.statusCode().is5xxServerError()) {
                return resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            var sc = resp.statusCode(); // HttpStatusCode
                            String reason = (sc instanceof org.springframework.http.HttpStatus hs)
                                    ? hs.getReasonPhrase()
                                    : sc.toString();
                            var ex = WebClientResponseException.create(
                                    sc.value(),
                                    reason,
                                    resp.headers().asHttpHeaders(),
                                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    java.nio.charset.StandardCharsets.UTF_8
                            );
                            return reactor.core.publisher.Mono.error(ex);
                        });
            }
            return reactor.core.publisher.Mono.just(resp);
        });

        WebClient.Builder b = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .baseUrl(sp.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + sp.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .filter(errorFilter);

        if (sp.getApiVersion() != null && !sp.getApiVersion().isBlank()) {
            b.defaultHeader("Stripe-Version", sp.getApiVersion());
        }

        return b.build();
    }


    private static void applyIdempotency(WebClient.RequestBodySpec spec, String prefix, String key) {
        if (key != null && !key.isBlank()) {
            spec.header("Idempotency-Key", (prefix == null || prefix.isBlank()) ? key : prefix + key);
        }
    }

  /* ===========================
     Models (subset we actually use)
     =========================== */

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Value
    public static class PaymentIntent {
        String id;
        String status;                // requires_payment_method, requires_confirmation, requires_action, processing, requires_capture, canceled, succeeded
        Long amount;                  // requested amount
        String currency;
        @JsonProperty("amount_capturable") Long amountCapturable;
        @JsonProperty("latest_charge") String latestCharge;
        Charges charges;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Charges {
            public List<Charge> data;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Value
    public static class Charge {
        String id;
        String status;     // succeeded, pending, failed
        Long amount;
        String currency;
        @JsonProperty("payment_intent") String paymentIntent;
        Boolean captured;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Value
    public static class Refund {
        String id;
        String status;     // succeeded, pending, failed, canceled
        String charge;
        Long amount;
        String currency;
    }

  /* ===========================
     Calls
     =========================== */

    /**
     * Create + (optionally) confirm a PaymentIntent for manual capture.
     */
    public Mono<PaymentIntent> createPaymentIntentManualCapture(long amountMinor,
                                                                String currency,
                                                                String reference,
                                                                String paymentMethodId,
                                                                boolean confirm,
                                                                String idempotencyKey) {
        Objects.requireNonNull(currency, "currency");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", String.valueOf(amountMinor));
        form.add("currency", currency.toLowerCase());
        form.add("capture_method", "manual");
        form.add("confirmation_method", "automatic");
        if (reference != null && !reference.isBlank()) form.add("description", reference);
        if (paymentMethodId != null && !paymentMethodId.isBlank()) form.add("payment_method", paymentMethodId);
        if (confirm) form.add("confirm", "true");

        PaymentProviderProperties.Stripe sp = props.getStripe();
        String idemPrefix = sp.effectiveIdempotencyPrefix(props.getCommon());

        WebClient.RequestBodySpec spec = client().post().uri("/v1/payment_intents");
        applyIdempotency(spec, idemPrefix, idempotencyKey);
        return spec.body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(PaymentIntent.class);
    }

    /**
     * Capture a PaymentIntent (partial or full). amountToCapture may be null for full.
     */
    public Mono<PaymentIntent> capturePaymentIntent(String paymentIntentId,
                                                    Long amountToCapture,
                                                    String idempotencyKey) {
        Objects.requireNonNull(paymentIntentId, "paymentIntentId");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (amountToCapture != null && amountToCapture > 0) {
            form.add("amount_to_capture", String.valueOf(amountToCapture));
        }
        PaymentProviderProperties.Stripe sp = props.getStripe();
        String idemPrefix = sp.effectiveIdempotencyPrefix(props.getCommon());

        WebClient.RequestBodySpec spec = client().post().uri("/v1/payment_intents/{id}/capture", paymentIntentId);
        applyIdempotency(spec, idemPrefix, idempotencyKey);
        return spec.body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(PaymentIntent.class);
    }

    /**
     * Create a refund. Prefer passing the captured charge id.
     * (Stripe also supports payment_intent param, but charge gives the crispest semantics.)
     */
    public Mono<Refund> createRefund(String chargeId, long amountMinor, String idempotencyKey) {
        Objects.requireNonNull(chargeId, "chargeId");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("charge", chargeId);
        form.add("amount", String.valueOf(amountMinor));

        PaymentProviderProperties.Stripe sp = props.getStripe();
        String idemPrefix = sp.effectiveIdempotencyPrefix(props.getCommon());

        WebClient.RequestBodySpec spec = client().post().uri("/v1/refunds");
        applyIdempotency(spec, idemPrefix, idempotencyKey);
        return spec.body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(Refund.class);
    }
}
