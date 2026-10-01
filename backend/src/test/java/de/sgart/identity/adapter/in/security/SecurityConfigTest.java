package de.sgart.identity.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit-tests the composed token validator directly — MockMvc's {@code jwt()} post-processor skips
 * the decoder, so only this proves a validly signed but subject-less token is rejected.
 */
class SecurityConfigTest {

    private static final String ISSUER = "https://issuer.example.test/realms/sgart";
    private static final String AUDIENCE = "sgart-backend";

    private final OAuth2TokenValidator<Jwt> validator = SecurityConfig.tokenValidator(ISSUER, AUDIENCE);

    @Test
    void tokenValidator_acceptsATokenWithIssuerAudienceAndSubject() {
        assertThat(validator.validate(token("anna-sub")).hasErrors()).isFalse();
    }

    @Test
    void tokenValidator_rejectsATokenWithoutASubject() {
        assertThat(validator.validate(token(null)).hasErrors()).isTrue();
    }

    @Test
    void tokenValidator_rejectsATokenWithABlankSubject() {
        assertThat(validator.validate(token("  ")).hasErrors()).isTrue();
    }

    @Test
    void tokenValidator_rejectsATokenFromAnotherIssuer() {
        Jwt token = tokenBuilder().issuer("https://other-issuer.example.test/realms/other").subject("anna-sub").build();

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    @Test
    void tokenValidator_rejectsATokenForAnotherAudience() {
        Jwt token = tokenBuilder().audience(List.of("some-other-service")).subject("anna-sub").build();

        assertThat(validator.validate(token).hasErrors()).isTrue();
    }

    private static Jwt token(String subject) {
        Jwt.Builder builder = tokenBuilder();
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.build();
    }

    private static Jwt.Builder tokenBuilder() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }
}
