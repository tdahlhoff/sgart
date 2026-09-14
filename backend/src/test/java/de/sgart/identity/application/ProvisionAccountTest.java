package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryProvisionedAccountRepository;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, {@link InMemoryProvisionedAccountRepository} + a fake {@link
 * CreateAccount} double, no framework (CLAUDE.md §6). Proves {@link ProvisionAccount}'s idempotent
 * provisioning and fail-fast validation (Story 7.1, AC1/AC3).
 */
class ProvisionAccountTest {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    /** A syntactically valid base64url encoding of 32 arbitrary bytes (a well-formed Ed25519 public key shape). */
    private static final String VALID_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private final InMemoryProvisionedAccountRepository provisionedAccountRepository =
            new InMemoryProvisionedAccountRepository();
    private final RecordingCreateAccount createAccount = new RecordingCreateAccount();
    private final ProvisionAccount provisionAccount =
            new ProvisionAccount(createAccount, provisionedAccountRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void provisionAccount_calledOnce_createsTheAccountAndRecordsTheRow() {
        provisionAccount.provision(VALID_PUBLIC_KEY, "ANDROID");

        assertThat(createAccount.callCount).isEqualTo(1);
        KeycloakUserId keycloakUserId = createAccount.lastCreatedId();
        assertThat(provisionedAccountRepository.contains(keycloakUserId)).isTrue();
        assertThat(provisionedAccountRepository.size()).isEqualTo(1);
    }

    @Test
    void provisionAccount_calledTwiceForSameDeviceKey_createsExactlyOneAccountAndRow() {
        provisionAccount.provision(VALID_PUBLIC_KEY, "ANDROID");
        provisionAccount.provision(VALID_PUBLIC_KEY, "ANDROID");

        // CreateAccount.create is idempotent itself (the real Keycloak adapter treats "already
        // exists" as success) — this double mirrors that by always resolving the same username to
        // the same id, so the repository row-count assertion is what actually proves idempotency.
        assertThat(provisionedAccountRepository.size()).isEqualTo(1);
    }

    @Test
    void provisionAccount_withMalformedPublicKey_returns4xxAndCreatesNothing() {
        assertThatThrownBy(() -> provisionAccount.provision("not-base64url!!!", "ANDROID"))
                .isInstanceOf(InvalidAccountProvisioningException.class)
                .extracting(exception -> ((InvalidAccountProvisioningException) exception).errorDescriptor().code())
                .isEqualTo("account.publicKeyInvalid");

        assertThat(createAccount.callCount).isZero();
        assertThat(provisionedAccountRepository.size()).isZero();
    }

    @Test
    void provisionAccount_withPublicKeyOfTheWrongLength_isRejected() {
        // Valid base64url, but decodes to fewer than 32 bytes.
        assertThatThrownBy(() -> provisionAccount.provision("AAAA", "ANDROID"))
                .isInstanceOf(InvalidAccountProvisioningException.class)
                .extracting(exception -> ((InvalidAccountProvisioningException) exception).errorDescriptor().code())
                .isEqualTo("account.publicKeyInvalid");
    }

    @Test
    void provisionAccount_withAMissingPublicKey_isRejected() {
        assertThatThrownBy(() -> provisionAccount.provision(null, "ANDROID"))
                .isInstanceOf(InvalidAccountProvisioningException.class)
                .extracting(exception -> ((InvalidAccountProvisioningException) exception).errorDescriptor().code())
                .isEqualTo("account.publicKeyRequired");
    }

    @Test
    void provisionAccount_withAnUnrecognizedPlatform_isRejected() {
        assertThatThrownBy(() -> provisionAccount.provision(VALID_PUBLIC_KEY, "WINDOWS_PHONE"))
                .isInstanceOf(InvalidAccountProvisioningException.class)
                .extracting(exception -> ((InvalidAccountProvisioningException) exception).errorDescriptor().code())
                .isEqualTo("account.platformInvalid");

        assertThat(createAccount.callCount).isZero();
    }

    /** Deterministic on username, exactly like the real Keycloak adapter (idempotent "already exists"). */
    private static final class RecordingCreateAccount implements CreateAccount {
        private final Map<String, KeycloakUserId> idsByUsername = new HashMap<>();
        private int callCount;

        @Override
        public KeycloakUserId create(String username, String publicKey) {
            callCount++;
            return idsByUsername.computeIfAbsent(username, KeycloakUserId::new);
        }

        KeycloakUserId lastCreatedId() {
            return Objects.requireNonNull(idsByUsername.get(VALID_PUBLIC_KEY));
        }
    }
}
