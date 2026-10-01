package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.shared.HouseholdId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Shared fast, in-memory test doubles for the Story 7.3 application-service unit tests (CLAUDE.md
 * §6: no framework, no infra) — kept in one place so each test file stays about its own behavior,
 * not about reimplementing the same fakes.
 */
final class RecoveryEmailTestSupport {

    private RecoveryEmailTestSupport() {}

    /**
     * Wraps {@code delegate} so that the named methods throw, like an unreachable database, while
     * every other method still reaches the real double.
     */
    static <T> T failingOn(Class<T> type, T delegate, String... failingMethodNames) {
        Set<String> failing = Set.of(failingMethodNames);
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
            if (failing.contains(method.getName())) {
                throw new IllegalStateException("unavailable: " + method.getName());
            }
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }));
    }

    /** A trivial, test-only hasher (identity-prefixed) so tests can assert on the exact hash without real HMAC. */
    static final class IdentityRecoveryCodeHasher implements RecoveryCodeHasher {
        @Override
        public String hash(String code) {
            return "hash:" + code;
        }
    }

    /** Always allows — the default throttle double for tests not about throttling itself. */
    static final class AlwaysAllowThrottles implements AttachRequestThrottle, AttachMailThrottle, RecoveryRequestThrottle {
        @Override
        public boolean tryAttach(KeycloakUserId caller) {
            return true;
        }

        @Override
        public boolean tryMail(RecoveryEmailDigest digest) {
            return true;
        }

        @Override
        public boolean tryRequest(RecoveryEmailDigest digest) {
            return true;
        }

        @Override
        public void reset(RecoveryEmailDigest digest) {}
    }

    /** Denies the attach budgets and the recovery budget selectively, to prove each refusal path. */
    static final class ConfigurableThrottles
            implements AttachRequestThrottle, AttachMailThrottle, RecoveryRequestThrottle {
        boolean attachRequestAllowed = true;
        boolean attachMailAllowed = true;
        boolean recoveryRequestAllowed = true;
        final List<RecoveryEmailDigest> resetDigests = new ArrayList<>();

        @Override
        public boolean tryAttach(KeycloakUserId caller) {
            return attachRequestAllowed;
        }

        @Override
        public boolean tryMail(RecoveryEmailDigest digest) {
            return attachMailAllowed;
        }

        @Override
        public boolean tryRequest(RecoveryEmailDigest digest) {
            return recoveryRequestAllowed;
        }

        @Override
        public void reset(RecoveryEmailDigest digest) {
            resetDigests.add(digest);
        }
    }

    /** A deterministic, test-only digester (plain SHA-256) that, like the real one, never contains the address. */
    static final class Sha256RecoveryEmailDigester implements RecoveryEmailDigester {

        static RecoveryEmailDigest digestOf(String normalizedAddress) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(normalizedAddress.getBytes(StandardCharsets.UTF_8));
                return new RecoveryEmailDigest(HexFormat.of().formatHex(hash));
            } catch (NoSuchAlgorithmException cause) {
                throw new IllegalStateException(cause);
            }
        }

        @Override
        public RecoveryEmailDigest digest(String normalizedAddress) {
            return digestOf(normalizedAddress);
        }
    }

    static final class RecordingSendRecoveryCodeEmail implements SendRecoveryCodeEmail {
        final List<String> attachMailRecipients = new ArrayList<>();
        final List<String> attachMailCodes = new ArrayList<>();
        final List<String> recoveryMailRecipients = new ArrayList<>();
        final List<String> recoveryMailCodes = new ArrayList<>();

        @Override
        public void sendAttachConfirmationCode(String address, String code) {
            attachMailRecipients.add(address);
            attachMailCodes.add(code);
        }

        @Override
        public void sendRecoveryCode(String address, String code) {
            recoveryMailRecipients.add(address);
            recoveryMailCodes.add(code);
        }
    }

    static final class FakeFindHouseholdNames implements FindHouseholdNames {
        private final Map<HouseholdId, String> namesByHouseholdId = new HashMap<>();

        void register(HouseholdId householdId, String householdName) {
            namesByHouseholdId.put(householdId, householdName);
        }

        @Override
        public Map<HouseholdId, String> namesFor(List<HouseholdId> householdIds) {
            Map<HouseholdId, String> known = new HashMap<>(namesByHouseholdId);
            known.keySet().retainAll(householdIds);
            return known;
        }
    }

    /** Runs every task at once on the calling thread, so a test sees the whole effect right after the call. */
    static final class SynchronousExecutor implements Executor {
        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }

    /** Holds every task back until the test runs it, to prove what happens before the executor gets to work. */
    static final class CapturingExecutor implements Executor {
        final List<Runnable> pendingTasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            pendingTasks.add(task);
        }

        void runPendingTasks() {
            pendingTasks.forEach(Runnable::run);
            pendingTasks.clear();
        }
    }

    static final class FakeGetAccountDetails implements GetAccountDetails {
        private final Map<KeycloakUserId, AccountDetails> byId = new HashMap<>();

        void register(KeycloakUserId id, String username, String publicKey) {
            byId.put(id, new AccountDetails(username, publicKey));
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

    /** A rebind that fails after the throwaway is already deleted (Keycloak 5xx / network). */
    static final class FailingRebindAccountCredential implements RebindAccountCredential {
        @Override
        public void rebind(KeycloakUserId keycloakUserId, String username, String publicKey) {
            throw new IllegalStateException("keycloak unavailable");
        }
    }

    static final class RecordingCreateAccount implements CreateAccount {
        record Creation(String username, String publicKey) {}

        final List<Creation> creations = new ArrayList<>();
        boolean shouldFail;
        /** When set, the "account" already exists under this id — Keycloak's idempotent 409 path. */
        KeycloakUserId existingHolder;

        @Override
        public KeycloakUserId create(String username, String publicKey) {
            if (shouldFail) {
                throw new IllegalStateException("keycloak still unavailable");
            }
            creations.add(new Creation(username, publicKey));
            return existingHolder != null ? existingHolder : new KeycloakUserId("restored-" + username);
        }
    }
}
