package com.github.dimitryivaniuta.orderservice.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import static org.springframework.security.core.context.ReactiveSecurityContextHolder.getContext;

@Component
public class SecurityTenantResolver {

    public record TenantCtx(String tenantId, UUID userId, String correlationId) {}
/*
    public Mono<TenantCtx> current() {
        return getContext().flatMap(sc -> {
            Authentication auth = sc.getAuthentication();
            if (auth instanceof JwtAuthenticationToken jat) {
                Jwt jwt = jat.getToken();
                String tenant = Optional.ofNullable(jwt.getClaimAsString("mt"))
                        .filter(s -> !s.isBlank())
                        .orElseThrow(() -> new IllegalStateException("Missing tenant claim 'mt'"));
                // sub must be UUID per your convention
                UUID user = UUID.fromString(jwt.getSubject());
                // pull correlation id from reactor context if present (or generate)
                return Mono.deferContextual(ctx ->
                        Mono.just(new TenantCtx(tenant, user, correlationId(ctx))));
            }
            return Mono.error(new IllegalStateException("Unsupported authentication"));
        });
    }
    */

    public Mono<TenantCtx> current() {
        return getContext().flatMap(sc -> {
            Authentication auth = sc.getAuthentication();
            if (auth instanceof JwtAuthenticationToken jat) {
                Jwt jwt = jat.getToken();

                String tenant = Optional.ofNullable(jwt.getClaimAsString("mt"))
                        .filter(s -> !s.isBlank())
                        .orElseThrow(() -> new IllegalStateException("Missing tenant claim 'mt'"));

                UUID user = resolveUserId(jwt); // <-- tolerant to non-UUID sub

                return Mono.deferContextual(ctx ->
                        Mono.just(new TenantCtx(tenant, user, correlationId(ctx))));
            }
            return Mono.error(new IllegalStateException("Unsupported authentication"));
        });
    }

    /** Prefer 'uid' claim; else use 'sub'. If not a UUID, derive a stable UUID from it. */
    private static UUID resolveUserId(Jwt jwt) {
        String raw = Optional.ofNullable(jwt.getClaimAsString("uid"))
                .filter(s -> !s.isBlank())
                .orElse(jwt.getSubject());

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("JWT is missing subject/uid");
        }

        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            // derive a deterministic UUID (name-based) from any identifier (e.g., "1")
            return UUID.nameUUIDFromBytes(("user:" + raw).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String correlationId(ContextView ctx) {
        String cid = ctx.getOrDefault("correlationId", null);
        return cid != null ? cid : UUID.randomUUID().toString();
    }




}
