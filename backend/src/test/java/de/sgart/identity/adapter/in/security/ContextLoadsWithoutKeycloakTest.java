package de.sgart.identity.adapter.in.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression guard for the #1 way this story breaks CI (Story 1.4 Dev Notes, "Do not break
 * contextLoads"): the {@code jwk-set-uri}-based decoder must never perform an eager network call
 * at context startup. Points the decoder at a deliberately unreachable host — the context must
 * still load, proving the JWK set is fetched lazily, on first token verification, not at boot.
 */
@SpringBootTest
class ContextLoadsWithoutKeycloakTest {

    @DynamicPropertySource
    static void unreachableJwkSetUri(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:1/realms/sgart/protocol/openid-connect/certs");
    }

    @Test
    void contextLoadsEvenThoughKeycloakIsUnreachable() {
        // Fails the build if the security config ever regresses to eager OIDC discovery.
    }
}
