package com.github.dimitryivaniuta.gateway.config;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;

/**
 * Validates the 'aud' claim contains at least one of the expected audiences.
 */
@Slf4j
public final class RequiredAudienceValidator implements OAuth2TokenValidator<Jwt> {

    /** Audiences allowed to access this API. (Sorted lexicographically for checkstyle.) */
    private final List<String> allowedAudiences;

    /**
     * Creates a new validator.
     *
     * @param allowedAudiences expected audiences in the 'aud' claim
     */
    public RequiredAudienceValidator(final List<String> allowedAudiences) {
        this.allowedAudiences = List.copyOf(allowedAudiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(final Jwt jwt) {
        List<String> aud = jwt.getAudience();
        boolean ok = aud != null && aud.stream().anyMatch(allowedAudiences::contains);
        return ok ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                "Token audience is not accepted", null));
    }
}
