package com.github.dimitryivaniuta.payment.service;

import static org.springframework.util.StringUtils.hasText;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Resolves tenant/user/correlation from the reactive SecurityContext.
 * No dependency on custom properties; uses common JWT claim names with safe fallbacks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityTenantResolver {

    public Mono<TenantCtx> current() {
        return getContext().flatMap(sc -> {
            Authentication auth = sc.getAuthentication();
            if (!(auth instanceof JwtAuthenticationToken jat)) {
                return Mono.error(new IllegalStateException("Unsupported authentication: " + auth));
            }
            Jwt jwt = jat.getToken();
            String tenant = resolveTenant(jwt);
            UUID user = resolveUser(jwt);

            return Mono.deferContextual(ctx ->
                    Mono.just(new TenantCtx(
                            tenant,
                            user,
                            correlationId(ctx.getOrDefault("X-Correlation-ID", null),
                                    ctx.getOrDefault("correlation-id", null))
                    ))
            );
        });
    }

    private Mono<SecurityContext> getContext() {
        return ReactiveSecurityContextHolder.getContext()
                .switchIfEmpty(Mono.error(new IllegalStateException("No SecurityContext")));
    }

    private String resolveTenant(Jwt jwt) {
        // try a few common keys: tenant_id / tenantId / mt / tid / tenant
        for (String key : List.of("tenant_id", "tenantId", "mt", "tid", "tenant")) {
            String v = jwt.getClaimAsString(key);
            if (hasText(v)) return v;
        }
        throw new IllegalStateException("Missing tenant claim (tried tenant_id, tenantId, mt, tid, tenant)");
    }

    private UUID resolveUser(Jwt jwt) {
        // try user_id / uid / userId / sub; fall back to subject; if not UUID, derive a stable name-UUID
        String raw = null;
        for (String key : List.of("user_id", "uid", "userId", "sub")) {
            String v = jwt.getClaimAsString(key);
            if (hasText(v)) { raw = v; break; }
        }
        if (!hasText(raw)) raw = jwt.getSubject();

        try {
            return UUID.fromString(raw);
        } catch (Exception ignore) {
            return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String correlationId(Object primary, Object secondary) {
        if (primary instanceof String s && hasText(s)) return s;
        if (secondary instanceof String s && hasText(s)) return s;
        return "corr-" + Instant.now().toEpochMilli();
    }

    @Value
    public static class TenantCtx {
        String tenantId;
        UUID userId;
        String correlationId;
    }
}
