package com.github.dimitryivaniuta.common.security;

import java.util.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Maps JWT -> Collection<GrantedAuthority>
 * - Scopes from "scope" (or "scp" fallback) -> SCOPE_xxx
 * - Optional tenant claim ("mt") -> TENANT_<value>
 * - Optional permissions claim ("perm") -> PERM_<value>
 */
public class MultiTenantAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final SecurityClaimsProperties props;
    private final JwtGrantedAuthoritiesConverter scopeConverter;

    public MultiTenantAuthoritiesConverter(SecurityClaimsProperties props) {
        this.props = props;
        this.scopeConverter = new JwtGrantedAuthoritiesConverter();
        this.scopeConverter.setAuthorityPrefix(props.getScopeAuthorityPrefix());
        this.scopeConverter.setAuthoritiesClaimName(props.getScopeClaim());
    }

    @Override
    public Collection<GrantedAuthority> convert(final Jwt jwt) {
        List<GrantedAuthority> out = new ArrayList<>();

        // Scopes
        Collection<GrantedAuthority> scopeAuth = scopeConverter.convert(jwt);
        if (scopeAuth != null) out.addAll(scopeAuth);

        // Fallback to "scp" if nothing came from primary scope claim and fallback enabled
        if (props.isFallbackScp() && (scopeAuth == null || scopeAuth.isEmpty())
                && !Claims.SCP.equals(props.getScopeClaim())) {
            JwtGrantedAuthoritiesConverter scp = new JwtGrantedAuthoritiesConverter();
            scp.setAuthorityPrefix(props.getScopeAuthorityPrefix());
            scp.setAuthoritiesClaimName(Claims.SCP);
            Collection<GrantedAuthority> a2 = scp.convert(jwt);
            if (a2 != null) out.addAll(a2);
        }

        // Tenant authority
        if (props.isAddTenantAuthority()) {
            JwtClaimUtils.getString(jwt, props.getTenantClaim())
                    .filter(s -> !s.isBlank())
                    .ifPresent(t -> out.add(new SimpleGrantedAuthority(props.getTenantAuthorityPrefix() + t)));
        }

        // Permissions
        List<String> perms = JwtClaimUtils.getStringList(jwt, props.getPermissionsClaim());
        for (String p : perms) {
            out.add(new SimpleGrantedAuthority(props.getPermissionAuthorityPrefix() + p));
        }

        return out;
    }
}
