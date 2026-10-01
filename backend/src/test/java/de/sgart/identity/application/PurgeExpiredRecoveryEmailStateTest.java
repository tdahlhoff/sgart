package de.sgart.identity.application;

import static de.sgart.identity.RecoveryEmailBindingFixtures.saveConfirmedBinding;
import static de.sgart.identity.RecoveryEmailBindingFixtures.saveConfirmedBinding;
import static de.sgart.identity.application.RecoveryEmailTestSupport.failingOn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.sgart.identity.CapturedLogs;
import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.application.RecoveryEmailTestSupport.InjectedFailures;
import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PurgeExpiredRecoveryEmailStateTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final KeycloakUserId STALE_ACCOUNT = new KeycloakUserId("stale-account");
    private static final KeycloakUserId FRESH_ACCOUNT = new KeycloakUserId("fresh-account");
    private static final KeycloakUserId CONFIRMED_ACCOUNT = new KeycloakUserId("confirmed-account");
    private static final RecoveryCodeSubject STALE_CODE_SUBJECT = RecoveryCodeSubject.forAccount(STALE_ACCOUNT);
    private static final RecoveryCodeSubject FRESH_CODE_SUBJECT = RecoveryCodeSubject.forAccount(FRESH_ACCOUNT);
    private static final RecoveryEmailHint HINT = RecoveryEmailHint.masking("person@example.test");

    private final InMemoryRecoveryEmailBindingRepository bindings = new InMemoryRecoveryEmailBindingRepository();
    private final InMemoryEmailRecoveryCodeStore codeStore = new InMemoryEmailRecoveryCodeStore();
    private final PurgeExpiredRecoveryEmailState purge =
            new PurgeExpiredRecoveryEmailState(bindings, codeStore, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void purge_deletesPendingBindingsOlderThanTheCodeLifetime() {
        Instant justPastTheLifetime = NOW.minus(RecoveryCode.TTL).minusSeconds(1);
        bindings.savePending(RecoveryEmailBinding.pending(new RecoveryEmailDigest("d1"), STALE_ACCOUNT, HINT, justPastTheLifetime));
        bindings.savePending(RecoveryEmailBinding.pending(
                new RecoveryEmailDigest("d2"), FRESH_ACCOUNT, HINT, NOW.minus(Duration.ofMinutes(1))));

        purge.purge();

        assertThat(bindings.findPendingFor(STALE_ACCOUNT)).isEmpty();
        assertThat(bindings.findPendingFor(FRESH_ACCOUNT)).isPresent();
    }

    @Test
    void purge_keepsConfirmedBindings() {
        saveConfirmedBinding(
                bindings, new RecoveryEmailDigest("d3"), CONFIRMED_ACCOUNT, HINT, NOW.minus(Duration.ofDays(30)));

        purge.purge();

        assertThat(bindings.hasConfirmedBindingFor(CONFIRMED_ACCOUNT)).isTrue();
    }

    @Test
    void purge_deletesExpiredCodes() {
        codeStore.store(STALE_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW.minusSeconds(1), NOW.minusSeconds(901));
        codeStore.store(FRESH_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW.plusSeconds(60), NOW);

        purge.purge();

        assertThat(codeStore.find(STALE_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
        assertThat(codeStore.find(FRESH_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM)).isPresent();
    }

    @Test
    void purge_deletesAnExpiredRecoveryCodeBelongingToAnAddress() {
        RecoveryCodeSubject expiredAddress = RecoveryCodeSubject.forAddress(new RecoveryEmailDigest("expired-address"));
        RecoveryCodeSubject activeAddress = RecoveryCodeSubject.forAddress(new RecoveryEmailDigest("active-address"));
        codeStore.store(expiredAddress, RecoveryCodePurpose.RECOVER, "hash", NOW.minusSeconds(1), NOW.minusSeconds(901));
        codeStore.store(activeAddress, RecoveryCodePurpose.RECOVER, "hash", NOW.plusSeconds(60), NOW);

        purge.purge();

        assertThat(codeStore.find(expiredAddress, RecoveryCodePurpose.RECOVER)).isEmpty();
        assertThat(codeStore.find(activeAddress, RecoveryCodePurpose.RECOVER)).isPresent();
    }

    @Test
    void purge_keepsAPendingBindingCreatedExactlyOneCodeLifetimeAgo() {
        // Current behavior, pinned: the cut-off is exclusive, so the binding that is exactly as old as
        // the code lifetime survives until the next purge run.
        bindings.savePending(RecoveryEmailBinding.pending(
                new RecoveryEmailDigest("d4"), STALE_ACCOUNT, HINT, NOW.minus(RecoveryCode.TTL)));

        purge.purge();

        assertThat(bindings.findPendingFor(STALE_ACCOUNT)).isPresent();
    }

    @Test
    void purge_keepsACodeThatExpiresExactlyNow() {
        // Current behavior, pinned: expiry is exclusive for the purge, so a code expiring at this very
        // instant is kept until the next run.
        codeStore.store(STALE_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW, NOW.minusSeconds(900));

        purge.purge();

        assertThat(codeStore.find(STALE_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM)).isPresent();
    }

    @Test
    void purge_whenThePendingBindingCleanupFails_stillDeletesTheExpiredCodes() {
        codeStore.store(STALE_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW.minusSeconds(1), NOW.minusSeconds(901));
        InjectedFailures injectedFailures = new InjectedFailures();
        PurgeExpiredRecoveryEmailState withFailingBindings = new PurgeExpiredRecoveryEmailState(
                failingOn(RecoveryEmailBindingRepository.class, bindings, injectedFailures, "deletePendingCreatedBefore"),
                codeStore,
                Clock.fixed(NOW, ZoneOffset.UTC));

        withFailingBindings.purge();

        assertThat(injectedFailures.hasFired("deletePendingCreatedBefore")).isTrue();
        assertThat(codeStore.find(STALE_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
    }

    @Test
    void purge_whenTheExpiredCodeCleanupFails_stillDeletesTheStalePendingBindings() {
        bindings.savePending(RecoveryEmailBinding.pending(
                new RecoveryEmailDigest("d1"), STALE_ACCOUNT, HINT, NOW.minus(RecoveryCode.TTL).minusSeconds(1)));
        InjectedFailures injectedFailures = new InjectedFailures();
        PurgeExpiredRecoveryEmailState withFailingCodes = new PurgeExpiredRecoveryEmailState(
                bindings,
                failingOn(EmailRecoveryCodeStore.class, codeStore, injectedFailures, "deleteExpiredBefore"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        withFailingCodes.purge();

        assertThat(injectedFailures.hasFired("deleteExpiredBefore")).isTrue();
        assertThat(bindings.findPendingFor(STALE_ACCOUNT)).isEmpty();
    }

    @Test
    void purge_whenBothCleanupsFail_neverThrows() {
        InjectedFailures injectedFailures = new InjectedFailures();
        PurgeExpiredRecoveryEmailState withEverythingFailing = new PurgeExpiredRecoveryEmailState(
                failingOn(RecoveryEmailBindingRepository.class, bindings, injectedFailures, "deletePendingCreatedBefore"),
                failingOn(EmailRecoveryCodeStore.class, codeStore, injectedFailures, "deleteExpiredBefore"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatCode(withEverythingFailing::purge).doesNotThrowAnyException();

        assertThat(injectedFailures.firedMethodNames())
                .containsExactlyInAnyOrder("deletePendingCreatedBefore", "deleteExpiredBefore");
    }

    @Test
    void purge_whenACleanupFails_logsNeitherTheAddressNorTheCodeFromTheFailureMessage() {
        String address = "leaky@example.test";
        String code = "123456";
        EmailRecoveryCodeStore leakingStore = (EmailRecoveryCodeStore) Proxy.newProxyInstance(
                EmailRecoveryCodeStore.class.getClassLoader(),
                new Class<?>[] {EmailRecoveryCodeStore.class},
                (proxy, method, arguments) -> {
                    throw new IllegalStateException("failed for " + address + " with code " + code);
                });
        PurgeExpiredRecoveryEmailState withLeakingFailure =
                new PurgeExpiredRecoveryEmailState(bindings, leakingStore, Clock.fixed(NOW, ZoneOffset.UTC));

        try (CapturedLogs logs = CapturedLogs.ofLoggerOf(PurgeExpiredRecoveryEmailState.class)) {
            withLeakingFailure.purge();

            assertThat(logs.hasLoggedAnything()).isTrue();
            assertThat(logs.allOutput()).doesNotContain(address).doesNotContain(code);
        }
    }
}
