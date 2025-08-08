package com.github.dimitryivaniuta.gateway.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Utility to convert Keycloak 'realm_access.roles' and 'resource_access.*.roles'
 * into Spring Security ROLE_* authorities.
 */
@Slf4j
public final class KeycloakAuthoritiesExtractor {

    private KeycloakAuthoritiesExtractor() {
        // utility
    }

    /**
     * Extracts ROLE_* authorities from a Keycloak JWT if claims are present.
     *
     * @param jwt the decoded JWT
     * @return list of ROLE_* authorities
     */
    public static List<SimpleGrantedAuthority> extract(final Jwt jwt) {
        List<SimpleGrantedAuthority> out = new ArrayList<>();

        Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
        if (realm != null && realm.get("roles") instanceof Collection<?> roles) {
            roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }

        Map<String, Object> resource = jwt.getClaimAsMap("resource_access");
        if (resource != null) {
            resource.values().forEach(val -> {
                if (val instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> rs) {
                    rs.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
                }
            });
        }
        return out;
    }
}
