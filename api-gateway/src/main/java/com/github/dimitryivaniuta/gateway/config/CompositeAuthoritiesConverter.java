package com.github.dimitryivaniuta.gateway.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

/**
 * Combines multiple Jwt->authorities converters (scopes + realm/resource roles).
 */
@Slf4j
@Component
public final class CompositeAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** Scope converter (SCOPE_*). */
    private final JwtGrantedAuthoritiesConverter scopeConverter;

    /** Role converter for Keycloak-style claims. */
    private final KeycloakRolesConverter rolesConverter;

    /** Default constructor initializes delegates. */
    public CompositeAuthoritiesConverter() {
        this.scopeConverter = new JwtGrantedAuthoritiesConverter();
        this.scopeConverter.setAuthorityPrefix("SCOPE_");
        this.scopeConverter.setAuthoritiesClaimName("scope"); // will also read 'scp' if present

        this.rolesConverter = new KeycloakRolesConverter();
    }

    @Override
    public Collection<GrantedAuthority> convert(final Jwt source) {
        Set<GrantedAuthority> out = new HashSet<>();
        out.addAll(scopeConverter.convert(source));
        out.addAll(rolesConverter.convert(source));
        return List.copyOf(out);
    }

    /**
     * Extracts Keycloak realm/resource roles -> ROLE_*.
     */
    static final class KeycloakRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        @Override
        public Collection<GrantedAuthority> convert(final Jwt jwt) {
            List<GrantedAuthority> out = new ArrayList<>();

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
}
