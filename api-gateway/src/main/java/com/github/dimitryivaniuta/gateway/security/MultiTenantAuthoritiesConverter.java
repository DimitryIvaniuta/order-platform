package com.github.dimitryivaniuta.gateway.security;

import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

/**
 * Combines:
 *  - global scopes -> SCOPE_*
 *  - multi-tenant roles from custom claim 'mt' -> TENANT_{tenant}:{ROLE}
 *  - (optional) audience -> AUD_{aud}
 *  - Keycloak fallback: resource_access['tenant:{id}'].roles -> TENANT_{id}:{ROLE}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiTenantAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** Mapping settings (alphabetical order). */
    private final MultiTenantAuthzProperties props;

    /** Reuses Spring’s scope converter and customizes prefix/claim. */
    private JwtGrantedAuthoritiesConverter scopeConverter() {
        JwtGrantedAuthoritiesConverter c = new JwtGrantedAuthoritiesConverter();
        c.setAuthorityPrefix(props.getScopeAuthorityPrefix());
        c.setAuthoritiesClaimName("scope"); // will also read 'scp' if present
        return c;
    }

    @Override
    public Collection<GrantedAuthority> convert(final Jwt jwt) {

        // 1) Global scopes -> SCOPE_*
        Set<GrantedAuthority> out = new HashSet<>(scopeConverter().convert(jwt));

        // 2) Multi-tenant roles from custom 'mt' claim
        Object mt = jwt.getClaim(props.getTenantClaim());
        if (mt instanceof Map<?,?> tenantsMap) {
            tenantsMap.forEach((tenantKey, rolesVal) -> {
                String tenant = String.valueOf(tenantKey);
                if (rolesVal instanceof Collection<?> roles) {
                    roles.forEach(r -> out.add(toTenantRoleAuthority(tenant, String.valueOf(r))));
                }
            });
        }

        // 3) Keycloak fallback: resource_access['tenant:{id}'].roles
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess != null && !resourceAccess.isEmpty()) {
            String prefix = props.getKeycloakTenantResourcePrefix();
            resourceAccess.forEach((res, val) -> {
                if (res != null && res.startsWith(prefix) && val instanceof Map<?,?> m) {
                    String tenant = res.substring(prefix.length());
                    Object rolesVal = m.get("roles");
                    if (rolesVal instanceof Collection<?> roles) {
                        roles.forEach(r -> out.add(toTenantRoleAuthority(tenant, String.valueOf(r))));
                    }
                }
            });
        }

        // 4) Optional: audience -> AUD_{aud}
        if (props.isMapAudienceToAuthorities()) {
            List<String> aud = jwt.getAudience();
            if (aud != null) {
                aud.forEach(a -> out.add(new SimpleGrantedAuthority(props.getAudienceAuthorityPrefix() + a)));
            }
        }
        return List.copyOf(out);
    }

    private GrantedAuthority toTenantRoleAuthority(final String tenant, final String role) {
        String name = String.format(props.getTenantRoleAuthorityPattern(), tenant, role);
        return new SimpleGrantedAuthority(name);
    }
}
