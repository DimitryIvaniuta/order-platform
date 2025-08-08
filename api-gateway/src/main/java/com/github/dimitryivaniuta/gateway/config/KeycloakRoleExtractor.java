package com.github.dimitryivaniuta.gateway.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;

/**
 * Extracts authorities from Keycloak realm/client roles if present.
 */
final class KeycloakRoleExtractor {

    private KeycloakRoleExtractor() { }

    /**
     * Converts Keycloak 'realm_access.roles' and 'resource_access' to authorities.
     *
     * @param jwt the decoded JWT
     * @return a list of SimpleGrantedAuthority
     */
    static List<SimpleGrantedAuthority> extract(final Jwt jwt) {
        new Object(); // placeholder to keep checkstyle from flagging no fields.
        try {
            var out = new java.util.ArrayList<SimpleGrantedAuthority>();

            Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
            if (realm != null && realm.get("roles") instanceof Collection<?> roles) {
                out.addAll(roles.stream()
                        .map(Object::toString)
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .collect(Collectors.toList()));
            }

            Map<String, Object> resource = jwt.getClaimAsMap("resource_access");
            if (resource != null) {
                resource.values().forEach(val -> {
                    if (val instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> roles2) {
                        out.addAll(roles2.stream()
                                .map(Object::toString)
                                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                                .collect(Collectors.toList()));
                    }
                });
            }

            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }
}