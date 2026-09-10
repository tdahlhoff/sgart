package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.application.FindHouseholdMemberByEmail;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Context-level test for the {@code FindHouseholdMemberByEmail} conditional wiring (Story 4.6, D4,
 * T2): the default profile must wire {@link DeferredFindHouseholdMemberByEmail} so the suite/CI/
 * local dev need no Keycloak Admin credentials; setting {@code
 * sgart.identity.keycloak-admin.enabled=true} (plus its required config) selects {@link
 * KeycloakAdminFindHouseholdMemberByEmail} instead. Uses the full {@code @SpringBootTest} context
 * (mirrors {@code ContextLoadsWithoutPostgresTest}) — building either bean performs no I/O.
 */
class IdentityBeansConfigTest {

    @SpringBootTest
    @Nested
    class KeycloakAdminDisabledByDefault {

        @Autowired
        private FindHouseholdMemberByEmail findHouseholdMemberByEmail;

        @Test
        void wiresDeferredImplementation() {
            assertThat(findHouseholdMemberByEmail).isInstanceOf(DeferredFindHouseholdMemberByEmail.class);
        }
    }

    @SpringBootTest
    @Nested
    class KeycloakAdminEnabled {

        @Autowired
        private FindHouseholdMemberByEmail findHouseholdMemberByEmail;

        @DynamicPropertySource
        static void keycloakAdminEnabled(DynamicPropertyRegistry registry) {
            registry.add("sgart.identity.keycloak-admin.enabled", () -> "true");
            registry.add("sgart.identity.keycloak-admin.base-url", () -> "http://keycloak.example");
            registry.add("sgart.identity.keycloak-admin.realm", () -> "sgart");
            registry.add("sgart.identity.keycloak-admin.client-id", () -> "sgart-admin");
            registry.add("sgart.identity.keycloak-admin.client-secret", () -> "admin-secret");
        }

        @Test
        void wiresKeycloakAdapter() {
            assertThat(findHouseholdMemberByEmail).isInstanceOf(KeycloakAdminFindHouseholdMemberByEmail.class);
        }
    }
}
