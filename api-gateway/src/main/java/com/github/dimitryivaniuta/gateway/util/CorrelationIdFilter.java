package com.github.dimitryivaniuta.gateway.util;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
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

    // Ordered

    /**
     * Run very early so the header is available to subsequent filters/handlers.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    // Helpers

    /**
     * Ensures the request contains an {@code X-Correlation-ID}; if missing, generates one,
     * mutates the request to include it, and stores it in the exchange attributes.
     *
     * @param exchange current server exchange
     * @return the correlation id value present after mutation
     */
    private String ensureCorrelationId(final ServerWebExchange exchange) {
        String id = exchange.getRequest().getHeaders().getFirst(HEADER_CORRELATION_ID);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .headers(h -> h.set(HEADER_CORRELATION_ID, id))
                    .build();
            exchange.mutate().request(mutated).build();
            if (log.isDebugEnabled()) {
                log.debug("Generated new correlation id {}", id);
            }
        }
        exchange.getAttributes().put(ATTR_CORRELATION_ID, id);
        return id;
    }

    /**
     * Utility to resolve the correlation id from an exchange (attribute first, then header).
     */
    public static String resolve(final ServerWebExchange exchange) {
        String id = exchange.getAttribute(ATTR_CORRELATION_ID);
        return (id != null) ? id : exchange.getRequest().getHeaders().getFirst(HEADER_CORRELATION_ID);
    }
}
