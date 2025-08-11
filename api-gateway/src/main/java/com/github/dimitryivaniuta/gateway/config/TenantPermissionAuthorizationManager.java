package com.github.dimitryivaniuta.gateway.config;

import java.util.Collection;
import java.util.function.Function;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import reactor.core.publisher.Mono;

/**
 * Checks TENANT_{tenant}:{requiredRole} against the authenticated authorities.
 */
public final class TenantPermissionAuthorizationManager
        implements ReactiveAuthorizationManager<AuthorizationContext> {

    private final Function<String, String> requiredAuthorityForTenant;

    public static TenantPermissionAuthorizationManager requires(final String role) {
        return new TenantPermissionAuthorizationManager(t -> "TENANT_" + t + ":" + role);
    }

    private TenantPermissionAuthorizationManager(final Function<String, String> f) {
        this.requiredAuthorityForTenant = f;
    }

    @Override
    public Mono<AuthorizationDecision> check(Mono<Authentication> auth, AuthorizationContext ctx) {
        String path = ctx.getExchange().getRequest().getPath().value();
        String tenant = extractTenantFromPath(path); // e.g., /t/{tenant}/...
        if (tenant == null || tenant.isBlank()) {
            return Mono.just(new AuthorizationDecision(false));
        }
        String needed = requiredAuthorityForTenant.apply(tenant);
        return auth.map(a -> new AuthorizationDecision(hasAuthority(a.getAuthorities(), needed)))
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

    private boolean hasAuthority(Collection<? extends org.springframework.security.core.GrantedAuthority> as, String needed) {
        return as.stream().anyMatch(ga -> ga.getAuthority().equals(needed));
    }

    private String extractTenantFromPath(String path) {
        // naive but fast: /t/{tenant}/...
        int i = path.indexOf("/t/");
        if (i < 0) return null;
        int start = i + 3;
        int end = path.indexOf('/', start);
        return end > start ? path.substring(start, end) : null;
    }
}
