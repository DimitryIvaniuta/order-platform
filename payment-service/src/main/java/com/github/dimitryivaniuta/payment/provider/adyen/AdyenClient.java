package com.github.dimitryivaniuta.payment.provider.adyen;

import java.util.Map;
import java.util.Objects;

import com.github.dimitryivaniuta.payment.api.dto.Amount;
import com.github.dimitryivaniuta.payment.api.dto.PaymentAuthResponse;
import com.github.dimitryivaniuta.payment.api.dto.PaymentCaptureResponse;
import com.github.dimitryivaniuta.payment.api.dto.PaymentRequest;
import com.github.dimitryivaniuta.payment.api.dto.RefundRequest;
import com.github.dimitryivaniuta.payment.api.dto.RefundResponse;
import com.github.dimitryivaniuta.payment.config.PaymentProviderProperties;
import com.github.dimitryivaniuta.payment.provider.adyen.dto.PaymentCaptureRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Thin, typed client for Adyen Checkout API.
 * Only the endpoints we need are implemented: /payments, /payments/{pspRef}/captures, /refunds.
 */
@Slf4j
//@Component
@RequiredArgsConstructor
public class AdyenClient {

    private final PaymentProviderProperties props;

    private WebClient client() {
        var read = props.getCommon().getTimeouts().getRead();

        HttpClient http = HttpClient.create()
                .responseTimeout(read)
                .compress(true);

        // Turn error HTTP statuses into WebClientResponseException with body included
        ExchangeFilterFunction errorFilter = ExchangeFilterFunction.ofResponseProcessor(resp -> {
            if (resp.statusCode().is4xxClientError() || resp.statusCode().is5xxServerError()) {
                return resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            var sc = resp.statusCode(); // HttpStatusCode (Spring 6)
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

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .baseUrl(props.getAdyen().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Api-Key " + props.getAdyen().getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .filter(errorFilter)
                .build();
    }

public Mono<PaymentAuthResponse> payments(PaymentRequest req, String idempotencyKey) {
        return client().post()
                .uri("/payments")
                .headers(h -> applyIdempotency(h, idempotencyKey))
                .body(BodyInserters.fromValue(req))
                .retrieve()
                    .bodyToMono(PaymentAuthResponse.class);
    }

    public Mono<PaymentCaptureResponse> captures(String authPspReference, PaymentCaptureRequest req, String idempotencyKey) {
        Objects.requireNonNull(authPspReference, "authPspReference");
        return client().post()
                .uri("/payments/{psp}/captures", authPspReference)
                .headers(h -> applyIdempotency(h, idempotencyKey))
                .body(BodyInserters.fromValue(req))
                .retrieve()
                .bodyToMono(PaymentCaptureResponse.class);
    }

    public Mono<RefundResponse> refunds(String captureOrAuthPspReference, RefundRequest req, String idempotencyKey) {
        Objects.requireNonNull(captureOrAuthPspReference, "pspReference");
        return client().post()
                .uri("/payments/{psp}/refunds", captureOrAuthPspReference)
                .headers(h -> applyIdempotency(h, idempotencyKey))
                .body(BodyInserters.fromValue(req))
                .retrieve()
                .bodyToMono(RefundResponse.class);
    }

    private void applyIdempotency(HttpHeaders headers, String idempotencyKey) {
        if (StringUtils.hasText(idempotencyKey)) {
            headers.add("Idempotency-Key", props.getCommon().getIdempotencyPrefix() + idempotencyKey);
        }
    }

    /** Convenience to build an Amount. */
    public static Amount amount(String currency, long minor) {
        return new Amount(currency, minor);
    }

    /** Null-safe map builder shortcut. */
    public static Map<String, Object> map(Object... kv) {
        if (kv == null || kv.length == 0) return Map.of();
        if (kv.length % 2 != 0) throw new IllegalArgumentException("map(..) requires even number of args");
        var b = new java.util.LinkedHashMap<String,Object>(kv.length/2);
        for (int i=0; i<kv.length; i+=2) {
            b.put(String.valueOf(kv[i]), kv[i+1]);
        }
        return b;
    }

    public PaymentProviderProperties.Adyen props() {
        return props.getAdyen();
    }

}
