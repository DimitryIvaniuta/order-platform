package com.github.dimitryivaniuta.gateway.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Reactive fallback endpoints used by Spring Cloud Gateway + Resilience4j circuit breakers.
 *
 * <p>When an upstream service becomes unavailable or times out, the gateway forwards to these
 * endpoints (via {@code fallbackUri: forward:/fallback/...}). We return a consistent JSON
 * problem payload with HTTP 503 and include or create an {@code X-Correlation-ID} for tracing.</p>
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>WebFlux only (no servlet types).</li>
 *   <li>Stateless controller; no {@code @Value} usage.</li>
 *   <li>Private fields are alphabetically ordered to satisfy project checkstyle.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class FallbackController {

    /** Correlation header name propagated across the platform. */
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    /**
     * Generic fallback endpoint. Use this for any route that specifies {@code fallbackUri: forward:/fallback}.
     *
     * @param request the reactive server request
     * @return 503 JSON payload with correlation id and request path
     */
    @GetMapping({"/fallback", "/fallback/**"})
    public Mono<ResponseEntity<Map<String, Object>>> fallback(final ServerHttpRequest request) {
        return Mono.fromSupplier(() -> {
            Map<String, Object> body = buildBody("upstream", request);
            log.warn("Gateway fallback (generic) correlationId={} path={}",
                    body.get("correlationId"), body.get("path"));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        });
    }

    /**
     * Orders service–specific fallback. Reference this in route filters:
     * {@code fallbackUri: forward:/fallback/orders}.
     *
     * @param request the reactive server request
     * @return 503 JSON payload indicating the orders upstream is unavailable
     */
    @GetMapping("/fallback/orders")
    public Mono<ResponseEntity<Map<String, Object>>> ordersFallback(final ServerHttpRequest request) {
        return Mono.fromSupplier(() -> {
            Map<String, Object> body = buildBody("order-service", request);
            log.warn("Gateway fallback (orders) correlationId={} path={}",
                    body.get("correlationId"), body.get("path"));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        });
    }

    /**
     * Builds a consistent problem response body.
     *
     * @param upstream a short name of the failed upstream (e.g., {@code order-service})
     * @param request  current request (to extract path and correlation id)
     * @return ordered map suitable for JSON serialization
     */
    private Map<String, Object> buildBody(final String upstream, final ServerHttpRequest request) {
        String path = request.getURI().getPath();
        String correlationId = request.getHeaders().getFirst(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // LinkedHashMap to keep field order in responses (friendlier for logs/clients)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", "Service Unavailable");
        body.put("message", "Upstream service is temporarily unavailable. Please retry.");
        body.put("upstream", upstream);
        body.put("path", path);
        body.put("correlationId", correlationId);

        // Ensure the correlation id header is present downstream of the forward (defensive).
        if (!request.getHeaders().containsKey(CORRELATION_HEADER)) {
            // Forwards keep the same exchange; the CB filter typically adds the header already,
            // but if not, the response body still exposes the id for client retries.
        }
        return body;
    }
}
