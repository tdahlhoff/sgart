package de.sgart.identity.adapter.in.security;

import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a JWT whose {@code aud} claim does not contain the SGART backend's expected audience
 * (AC1 — "issuer/audience checked"). Boot's {@code jwk-set-uri}-based auto-configuration performs
 * no audience check on its own, so {@link SecurityConfig} composes this validator explicitly.
 */
final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_AUDIENCE =
            new OAuth2Error("invalid_token", "The required audience is missing", null);

    private final String requiredAudience;

    AudienceValidator(String requiredAudience) {
        this.requiredAudience = Objects.requireNonNull(requiredAudience, "requiredAudience must not be null");
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audience = token.getAudience();
        if (audience != null && audience.contains(requiredAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
    }
}
