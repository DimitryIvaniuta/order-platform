package com.github.dimitryivaniuta.gateway.security;

import java.util.*;

import static java.util.Locale.ROOT;

/**
 * Central mapping: role -> OAuth2 scopes.
 */
public enum SystemRole {

    ADMIN(Set.of("admin", "orders.read", "orders.write")),
    ORDER_READ(Set.of("orders.read")),
    ORDER_WRITE(Set.of("orders.write")),
    USER(Set.of("orders.read"));

    private final Set<String> scopes;

    SystemRole(Set<String> scopes) {
        this.scopes = scopes;
    }

    public Set<String> scopes() {
        return scopes;
    }

    /**
     * Safe parser; returns empty if the value isn't a known enum constant.
     */
    public static Optional<SystemRole> from(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        try {
            return Optional.of(SystemRole.valueOf(name.trim().toUpperCase(ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
