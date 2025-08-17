package com.github.dimitryivaniuta.common.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mapping settings for scopes, tenant roles, Keycloak fallback, audience → authorities.
 */
@Getter @Setter
@ConfigurationProperties(prefix = "security.authz")
public class MultiTenantAuthzProperties {

    /** Prefix for scope-derived authorities (Spring expects SCOPE_). */
    private String scopeAuthorityPrefix = "SCOPE_";

    /** Claim that holds tenant→roles (your custom claim, usually "mt"). */
    private String tenantClaim = "mt";

    /** Pattern for TENANT role authorities, e.g. "TENANT_%s:%s" (tenant, role). */
    private String tenantRoleAuthorityPattern = "TENANT_%s:%s";

    /** Keycloak resource prefix encoding tenant in resource_access, e.g. "tenant:". */
    private String keycloakTenantResourcePrefix = "tenant:";

    /** If true, also emit AUD_<audience> authorities. */
    private boolean mapAudienceToAuthorities = false;

    /** Prefix for audience-derived authorities. */
    private String audienceAuthorityPrefix = "AUD_";
}
