package com.github.dimitryivaniuta.common.security;

import java.util.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;

/** Validates that token 'aud' contains at least one of the required values. */
public final class RequiredAudienceValidator implements OAuth2TokenValidator<Jwt> {
    private final Set<String> required;

    public RequiredAudienceValidator(Collection<String> required) {
        this.required = Set.copyOf(required);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> aud = token.getAudience();
        boolean ok = aud != null && aud.stream().anyMatch(required::contains);
        return ok ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token","Required audience missing", null));
    }
}
