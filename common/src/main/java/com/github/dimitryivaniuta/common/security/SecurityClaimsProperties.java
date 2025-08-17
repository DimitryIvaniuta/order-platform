package com.github.dimitryivaniuta.common.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** How we map JWT claims → Spring authorities. */
@Getter @Setter
@ConfigurationProperties(prefix = "security.claims")
public class SecurityClaimsProperties {

    /** Name of claim carrying scopes; default "scope", fallback "scp" if missing. */
    private String scopeClaim = Claims.SCOPE;

    /** Whether to also check "scp" if scopeClaim missing. */
    private boolean fallbackScp = true;

    /** Prefix for scope-derived authorities (Spring expects "SCOPE_"). */
    private String scopeAuthorityPrefix = "SCOPE_";

    /** Name of the tenant claim. */
    private String tenantClaim = Claims.TENANT;

    /** If true, add TENANT_<value> as an authority when tenant claim present. */
    private boolean addTenantAuthority = false;

    /** Prefix used when addTenantAuthority is true. */
    private String tenantAuthorityPrefix = "TENANT_";

    /** Optional permissions claim (array or delimited). */
    private String permissionsClaim = Claims.PERM;

    /** If set, map permissions to authorities with this prefix (e.g., "PERM_"). */
    private String permissionAuthorityPrefix = "PERM_";

}
