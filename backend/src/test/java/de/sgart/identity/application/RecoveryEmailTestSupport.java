package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared fast, in-memory test doubles for the Story 7.3 application-service unit tests (CLAUDE.md
 * §6: no framework, no infra) — kept in one place so each test file stays about its own behavior,
 * not about reimplementing the same fakes.
 */
final class RecoveryEmailTestSupport {

    private RecoveryEmailTestSupport() {}

    /** A trivial, test-only hasher (identity-prefixed) so tests can assert on the exact hash without real HMAC. */
    static final class IdentityRecoveryCodeHasher implements RecoveryCodeHasher {
        @Override
        public String hash(String code) {
            return "hash:" + code;
        }
    }

    static final class RecordingSetAccountEmail implements SetAccountEmail {
        private final Map<KeycloakUserId, String> emails = new HashMap<>();
        private final Map<KeycloakUserId, Boolean> verified = new HashMap<>();

        String emailFor(KeycloakUserId id) {
            return emails.get(id);
        }

        Boolean verifiedFor(KeycloakUserId id) {
            return verified.get(id);
        }

        @Override
        public void setEmail(KeycloakUserId keycloakUserId, String email, boolean emailVerified) {
            emails.put(keycloakUserId, email);
            verified.put(keycloakUserId, emailVerified);
        }

        @Override
        public void markEmailVerified(KeycloakUserId keycloakUserId) {
            verified.put(keycloakUserId, true);
        }

        @Override
        public void clearEmail(KeycloakUserId keycloakUserId) {
            emails.remove(keycloakUserId);
            verified.put(keycloakUserId, false);
        }
    }

    static final class RecordingSendRecoveryCodeEmail implements SendRecoveryCodeEmail {
        final List<String> sentTo = new ArrayList<>();
        final List<String> sentCodes = new ArrayList<>();

        @Override
        public void send(String email, String code) {
            sentTo.add(email);
            sentCodes.add(code);
        }
    }

    static final class FakeFindAccountByEmail implements FindAccountByEmail {
        private final Map<String, KeycloakUserId> byEmail = new HashMap<>();

        void registerAccount(String email, KeycloakUserId id) {
            byEmail.put(email, id);
        }

        @Override
        public Optional<KeycloakUserId> findByEmail(String email) {
            return Optional.ofNullable(byEmail.get(email));
        }
    }

    static final class FakeGetAccountDetails implements GetAccountDetails {
        private final Map<KeycloakUserId, AccountDetails> byId = new HashMap<>();

        void register(KeycloakUserId id, String username, String publicKey) {
            byId.put(id, new AccountDetails(username, publicKey, null, false));
        }

        /** The sweep's D-G "activated" case (design §7): a confirmed Keycloak email. */
        void confirmedEmailFor(KeycloakUserId id) {
            byId.put(id, new AccountDetails("u", "k", "a@example.com", true));
        }

        /** An attached-but-never-confirmed email — still sweepable past the TTL. */
        void unconfirmedEmailFor(KeycloakUserId id) {
            byId.put(id, new AccountDetails("u", "k", "a@example.com", false));
        }

        @Override
        public Optional<AccountDetails> findById(KeycloakUserId keycloakUserId) {
            return Optional.ofNullable(byId.get(keycloakUserId));
        }
    }

    static final class RecordingRebindAccountCredential implements RebindAccountCredential {
        record Rebind(KeycloakUserId keycloakUserId, String username, String publicKey) {}

        final List<Rebind> rebinds = new ArrayList<>();

        @Override
        public void rebind(KeycloakUserId keycloakUserId, String username, String publicKey) {
            rebinds.add(new Rebind(keycloakUserId, username, publicKey));
        }
    }

    static final class RecordingDeleteAccount implements DeleteAccount {
        final Set<KeycloakUserId> deletedIds = new HashSet<>();
        final List<KeycloakUserId> deletionOrder = new ArrayList<>();

        @Override
        public void delete(KeycloakUserId keycloakUserId) {
            deletedIds.add(keycloakUserId);
            deletionOrder.add(keycloakUserId);
        }
    }

    /**
     * Delete and rebind doubles sharing one ordered log (Story 7.3 review finding) — unlike {@link
     * RecordingDeleteAccount}/{@link RecordingRebindAccountCredential}, whose independent lists
     * cannot prove *relative* ordering between the two calls, appending both events to one shared
     * timeline lets a test assert the delete-before-rebind sequence the R1 rebind's Keycloak
     * username-uniqueness constraint depends on (design §1.1).
     */
    static final class OrderedDeleteAccount implements DeleteAccount {
        private final List<String> sharedLog;

        OrderedDeleteAccount(List<String> sharedLog) {
            this.sharedLog = sharedLog;
        }

        @Override
        public void delete(KeycloakUserId keycloakUserId) {
            sharedLog.add("delete:" + keycloakUserId.value());
        }
    }

    static final class OrderedRebindAccountCredential implements RebindAccountCredential {
        private final List<String> sharedLog;

        OrderedRebindAccountCredential(List<String> sharedLog) {
            this.sharedLog = sharedLog;
        }

        @Override
        public void rebind(KeycloakUserId keycloakUserId, String username, String publicKey) {
            sharedLog.add("rebind:" + keycloakUserId.value());
        }
    }
}
