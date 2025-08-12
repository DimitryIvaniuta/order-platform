package com.github.dimitryivaniuta.gateway.dto;

import lombok.Data;

/** Token response following OAuth 2.0 Bearer token response fields. */
@Data
public final class TokenResponse {
    /** Access token in compact JWS form. */
    private final String access_token;

    /** Token type, always "Bearer". */
    private final String token_type;

    /** Expiration in seconds. */
    private final int expires_in;

    /** Issued-at in epoch seconds. */
    private final long issued_at;

    /** Granted scopes (space-delimited). */
    private final String scope;

    /** Tenant identifier echoed back for client convenience. */
    private final String tenant_id;
}

