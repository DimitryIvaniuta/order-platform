package com.github.dimitryivaniuta.common.security;

import java.util.*;
import java.util.stream.Stream;
import org.springframework.security.oauth2.jwt.Jwt;

public final class JwtClaimUtils {
    private JwtClaimUtils() {}

    /** Read claim that may be array OR space/comma-delimited string. */
    public static List<String> getStringList(Jwt jwt, String claim) {
        if (jwt == null || claim == null) return List.of();
        Object v = jwt.getClaim(claim);
        if (v == null) return List.of();
        if (v instanceof Collection<?> c) {
            return c.stream().map(Object::toString).filter(s -> !s.isBlank()).toList();
        }
        String s = v.toString().trim();
        if (s.isEmpty()) return List.of();
        return Stream.of(s.split("[,\\s]+")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    public static Optional<String> getString(Jwt jwt, String claim) {
        if (jwt == null || claim == null) return Optional.empty();
        String v = jwt.getClaimAsString(claim);
        return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
    }
}
