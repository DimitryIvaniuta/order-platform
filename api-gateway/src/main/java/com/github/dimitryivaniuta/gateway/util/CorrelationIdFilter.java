package com.github.dimitryivaniuta.gateway.util;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Ensures every request carries a correlation id:
 * <ul>
 *   <li>Reads or creates {@code X-Correlation-ID}.</li>
 *   <li>Writes the header to outgoing proxied requests and to the HTTP response.</li>
 *   <li>Exposes the id via exchange attribute and Reactor Context for downstream code.</li>
 * </ul>
 *
 * <p>This class implements both {@link GlobalFilter} (for Gateway-proxied routes)
 * and {@link WebFilter} (for local WebFlux controllers like fallbacks or auth endpoints),
 * so the behavior is consistent for all entry paths.</p>
 */
@Slf4j
@Component
public class CorrelationIdFilter implements GlobalFilter, WebFilter, Ordered {

    /** Exchange attribute key containing the correlation id. */
    public static final String ATTR_CORRELATION_ID = "com.github.dimitryivaniuta.gateway.correlation-id";

    /** Reactor context key containing the correlation id. */
    public static final String CTX_CORRELATION_ID = "correlationId";

    /** HTTP header name for correlation id propagation. */
    public static final String HEADER_CORRELATION_ID = "X-Correlation-ID";

    // GlobalFilter (Gateway-proxied paths)

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        final String id = ensureCorrelationId(exchange);
        // Add response header immediately so clients always receive the id
        exchange.getResponse().getHeaders().set(HEADER_CORRELATION_ID, id);
        return chain.filter(exchange).contextWrite(ctx -> ctx.put(CTX_CORRELATION_ID, id));
    }

    // WebFilter (local controller paths)

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        final String id = ensureCorrelationId(exchange);
        exchange.getResponse().getHeaders().set(HEADER_CORRELATION_ID, id);
        return chain.filter(exchange).contextWrite(ctx -> ctx.put(CTX_CORRELATION_ID, id));
    }

    /**
     * Run very early so the header is available to subsequent filters/handlers.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    /**
     * Ensures the request contains an {@code X-Correlation-ID}; if missing, generates one,
     * mutates the request to include it, and stores it in the exchange attributes.
     *
     * @param exchange current server exchange
     * @return the correlation id value present after mutation
     */
    private String ensureCorrelationId(final ServerWebExchange exchange) {
        // 1) If another filter already stored it, reuse and mirror on the response.
        final String cached = exchange.getAttribute(ATTR_CORRELATION_ID);
        if (cached != null && !cached.isBlank()) {
            exchange.getResponse().getHeaders().set(HEADER_CORRELATION_ID, cached);
            return cached;
        }

        // 2) Read from request header and normalize.
        final String raw = exchange.getRequest().getHeaders().getFirst(HEADER_CORRELATION_ID);
        String cid = normalizeCorrelationId(raw);

        // 3) If missing/invalid, generate and mutate the request with an effectively-final var.
        if (cid == null) {
            cid = UUID.randomUUID().toString();
            final String toSet = cid; // effectively final for the lambda
            final ServerHttpRequest mutated = exchange.getRequest()
                    .mutate()
                    .headers(h -> h.set(HEADER_CORRELATION_ID, toSet))
                    .build();
            exchange.mutate().request(mutated).build();
            if (log.isDebugEnabled()) {
                log.debug("Generated new correlation id {}", toSet);
            }
        }

        // 4) Expose for downstream code and mirror to response for clients.
        exchange.getAttributes().put(ATTR_CORRELATION_ID, cid);
        exchange.getResponse().getHeaders().set(HEADER_CORRELATION_ID, cid);
        return cid;
    }

    /**
     * Returns a sanitized correlation id or null if the incoming value is unusable.
     * Simple guards prevent header abuse (blank/oversized values).
     */
    private String normalizeCorrelationId(final String raw) {
        if (raw == null) return null;
        final String v = raw.trim();
        if (v.isEmpty()) return null;
        if (v.length() > 200) return null; // defensive size cap
        return v;
    }

}
