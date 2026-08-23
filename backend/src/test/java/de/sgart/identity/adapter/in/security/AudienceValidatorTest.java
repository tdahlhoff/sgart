package de.sgart.identity.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit-tests {@link AudienceValidator} directly — the {@code IdentityControllerTest} uses the mock
 * {@code jwt()} post-processor, which never exercises this validator (AC1, "issuer/audience
 * checked").
 */
class AudienceValidatorTest {

    private static final String REQUIRED_AUDIENCE = "sgart-backend";

    private final AudienceValidator validator = new AudienceValidator(REQUIRED_AUDIENCE);

    @Test
    void accepts_aTokenWhoseAudienceContainsTheRequiredAudience() {
        Jwt token = jwtWithAudience(List.of("sgart-backend", "account"));

        assertThat(validator.validate(token).hasErrors()).isFalse();
    }

    @Test
    void rejects_aTokenWhoseAudienceDoesNotContainTheRequiredAudience() {
        Jwt token = jwtWithAudience(List.of("some-other-service"));

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    @Test
    void rejects_aTokenWithoutAnAudienceClaimInsteadOfThrowing() {
        Jwt token = jwtWithAudience(null);

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    private static Jwt jwtWithAudience(List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("anna-sub");
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }
}
