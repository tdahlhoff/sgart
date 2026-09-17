package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.application.CreateAccount;
import de.sgart.identity.application.DeleteAccount;
import de.sgart.identity.application.FindHouseholdMemberByEmail;
import de.sgart.identity.application.SendRecoveryCodeEmail;
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

    /**
     * Story 7.1: {@link CreateAccount}/{@link DeleteAccount} share the same {@code
     * sgart.identity.keycloak-admin.enabled} gate as {@link FindHouseholdMemberByEmail} above — the
     * default profile must wire the {@code Deferred*} stand-ins so no admin credentials are needed.
     */
    @SpringBootTest
    @Nested
    class AccountProvisioningKeycloakAdminDisabledByDefault {

        @Autowired
        private CreateAccount createAccount;

        @Autowired
        private DeleteAccount deleteAccount;

        @Test
        void wiresDeferredImplementations() {
            assertThat(createAccount).isInstanceOf(DeferredCreateAccount.class);
            assertThat(deleteAccount).isInstanceOf(DeferredDeleteAccount.class);
        }
    }

    @SpringBootTest
    @Nested
    class AccountProvisioningKeycloakAdminEnabled {

        @Autowired
        private CreateAccount createAccount;

        @Autowired
        private DeleteAccount deleteAccount;

        @DynamicPropertySource
        static void keycloakAdminEnabled(DynamicPropertyRegistry registry) {
            registry.add("sgart.identity.keycloak-admin.enabled", () -> "true");
            registry.add("sgart.identity.keycloak-admin.base-url", () -> "http://keycloak.example");
            registry.add("sgart.identity.keycloak-admin.realm", () -> "sgart");
            registry.add("sgart.identity.keycloak-admin.client-id", () -> "sgart-admin");
            registry.add("sgart.identity.keycloak-admin.client-secret", () -> "admin-secret");
        }

        @Test
        void wiresTheSameKeycloakAdapterForBothPorts() {
            assertThat(createAccount).isInstanceOf(KeycloakAdminCreateAccount.class);
            assertThat(deleteAccount).isInstanceOf(KeycloakAdminCreateAccount.class);
            assertThat(createAccount).isSameAs(deleteAccount);
        }
    }

    /**
     * Story 7.3, AC6: {@code sgart.identity.mail.enabled} absent/false must wire the no-op — the
     * context loads and {@code contextLoads}-style guarantees hold with no SMTP server reachable.
     */
    @SpringBootTest
    @Nested
    class MailSenderDisabledByDefault {

        @Autowired
        private SendRecoveryCodeEmail sendRecoveryCodeEmail;

        @Test
        void mailSenderNoOpIsWiredWhenMailDisabled_soContextLoadsWithoutSmtp() {
            assertThat(sendRecoveryCodeEmail).isInstanceOf(DeferredSendRecoveryCodeEmail.class);
        }
    }

    @SpringBootTest
    @Nested
    class MailSenderEnabled {

        @Autowired
        private SendRecoveryCodeEmail sendRecoveryCodeEmail;

        @DynamicPropertySource
        static void mailEnabled(DynamicPropertyRegistry registry) {
            registry.add("sgart.identity.mail.enabled", () -> "true");
            registry.add("sgart.identity.mail.from", () -> "no-reply@sgart.example");
        }

        @Test
        void wiresTheRealJavaMailSenderAdapter() {
            assertThat(sendRecoveryCodeEmail).isInstanceOf(JavaMailSenderRecoveryCodeEmail.class);
        }
    }
}
