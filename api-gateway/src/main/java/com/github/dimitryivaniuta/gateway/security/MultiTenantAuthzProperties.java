package com.github.dimitryivaniuta.gateway.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for JWT -> authorities mapping (scopes + multi-tenant roles).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "security.authz")
public class MultiTenantAuthzProperties {

    /** JSON claim that carries tenant->roles mapping. Default 'mt'. */
    private String tenantClaim = "mt";

    /** Optional: read Keycloak resource_access where resource key starts with this prefix (e.g., 'tenant:'). */
    private String keycloakTenantResourcePrefix = "tenant:";

    /** Prefix used for authorities derived from scopes. */
    private String scopeAuthorityPrefix = "SCOPE_";

    /** Pattern for tenant-role authorities: String.format(pattern, tenant, role). */
    private String tenantRoleAuthorityPattern = "TENANT_%s:%s";

    /** Whether to also map 'aud' values into authorities (e.g., 'AUD_gateway'). */
    private boolean mapAudienceToAuthorities = false;

    /** Prefix when mapping 'aud' to authorities. */
    private String audienceAuthorityPrefix = "AUD_";

    /** Header to read tenant id from when doing request-time checks. */
    private String tenantHeader = "X-Tenant-ID";
}
