package de.sgart.identity.application;

import static de.sgart.identity.RecoveryEmailBindingFixtures.saveConfirmedBinding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static de.sgart.identity.application.RecoveryEmailTestSupport.recordingCalls;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailThrottles;
import de.sgart.identity.CapturedLogs;
import de.sgart.identity.application.RecoveryEmailTestSupport.CapturingExecutor;
import de.sgart.identity.application.RecoveryEmailTestSupport.ConfigurableThrottles;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingSendRecoveryCodeEmail;
import de.sgart.identity.application.RecoveryEmailTestSupport.Sha256RecoveryEmailDigester;
import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test for the recover-by-email request: one code and one mail per mailbox whatever the
 * number of accounts bound to it, nothing for an unknown, pending-only, or over-budget address
 * (the controller answers {@code 202} regardless, so there is no account or address
 * enumeration), and the whole issuance runs on the executor.
 */
class RequestEmailRecoveryCodeTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final String ADDRESS = "person@example.test";
    private static final RecoveryEmailDigest DIGEST = Sha256RecoveryEmailDigester.digestOf(ADDRESS);
    private static final RecoveryCodeSubject CODE_SUBJECT = RecoveryCodeSubject.forAddress(DIGEST);
    private static final KeycloakUserId FIRST_ACCOUNT = new KeycloakUserId("account-1");
    private static final KeycloakUserId SECOND_ACCOUNT = new KeycloakUserId("account-2");

    private final InMemoryRecoveryEmailBindingRepository bindings = new InMemoryRecoveryEmailBindingRepository();
    private final InMemoryEmailRecoveryCodeStore emailRecoveryCodeStore = new InMemoryEmailRecoveryCodeStore();
    private final IdentityRecoveryCodeHasher hasher = new IdentityRecoveryCodeHasher();
    private final RecordingSendRecoveryCodeEmail sendRecoveryCodeEmail = new RecordingSendRecoveryCodeEmail();
    private final ConfigurableThrottles throttles = new ConfigurableThrottles();
    private final CapturingExecutor executor = new CapturingExecutor();
    private final RequestEmailRecoveryCode requestEmailRecoveryCode = new RequestEmailRecoveryCode(
            bindings,
            emailRecoveryCodeStore,
            hasher,
            sendRecoveryCodeEmail,
            new Sha256RecoveryEmailDigester(),
            throttles,
            executor,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private void bindConfirmed(KeycloakUserId account) {
        saveConfirmedBinding(bindings, DIGEST, account, RecoveryEmailHint.masking(ADDRESS), NOW);
    }

    @Test
    void request_withConfirmedBindings_storesOneCodeForTheAddressAndSendsOneMail() {
        bindConfirmed(FIRST_ACCOUNT);

        requestEmailRecoveryCode.request(ADDRESS);
        executor.runPendingTasks();

        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isPresent();
        assertThat(sendRecoveryCodeEmail.recoveryMailRecipients).containsExactly(ADDRESS);
    }

    @Test
    void request_withADifferentlyCasedAddress_stillIssuesTheCode() {
        bindConfirmed(FIRST_ACCOUNT);

        requestEmailRecoveryCode.request("  Person@Example.TEST ");
        executor.runPendingTasks();

        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isPresent();
        assertThat(sendRecoveryCodeEmail.recoveryMailRecipients).containsExactly(ADDRESS);
    }

    @Test
    void request_forAnAddressOnTwoAccounts_stillSendsExactlyOneMail() {
        bindConfirmed(FIRST_ACCOUNT);
        bindConfirmed(SECOND_ACCOUNT);

        requestEmailRecoveryCode.request(ADDRESS);
        executor.runPendingTasks();

        assertThat(sendRecoveryCodeEmail.recoveryMailRecipients).hasSize(1);
        assertThat(emailRecoveryCodeStore.size()).isEqualTo(1);
    }

    @Test
    void request_withAnUnknownAddress_sendsNothingAndStoresNothing() {
        requestEmailRecoveryCode.request("nobody@example.test");
        executor.runPendingTasks();

        assertThat(sendRecoveryCodeEmail.recoveryMailRecipients).isEmpty();
        assertThat(emailRecoveryCodeStore.size()).isZero();
    }

    @Test
    void request_withOnlyAPendingBinding_sendsNothing() {
        bindings.savePending(RecoveryEmailBinding.pending(DIGEST, FIRST_ACCOUNT, RecoveryEmailHint.masking(ADDRESS), NOW));

        requestEmailRecoveryCode.request(ADDRESS);
        executor.runPendingTasks();

        assertThat(sendRecoveryCodeEmail.recoveryMailRecipients).isEmpty();
        assertThat(emailRecoveryCodeStore.size()).isZero();
    }

    @Test
    void request_overTheAddressBudget_sendsNothingAndKeepsTheEarlierCodeUsable() {
        bindConfirmed(FIRST_ACCOUNT);
        requestEmailRecoveryCode.request(ADDRESS);
        executor.runPendingTasks();
        String earlierCodeHash = emailRecoveryCodeStore
                .find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)
                .orElseThrow()
                .codeHash();
        throttles.recoveryRequestAllowed = false;

        requestEmailRecoveryCode.request(ADDRESS);
        executor.runPendingTasks();

        assertThat(sendRecoveryCodeEmail.recoveryMailRecipients).hasSize(1);
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER).orElseThrow().codeHash())
                .isEqualTo(earlierCodeHash);
    }

    @Test
    void request_runsTheWholeIssuanceOnTheExecutor() {
        bindConfirmed(FIRST_ACCOUNT);

        requestEmailRecoveryCode.request(ADDRESS);

        assertThat(emailRecoveryCodeStore.size()).isZero();
        assertThat(sendRecoveryCodeEmail.recoveryMailRecipients).isEmpty();
        assertThat(executor.pendingTasks).hasSize(1);
    }

    @Test
    void request_withAMalformedAddress_isRejectedBeforeAnythingIsHandedToTheExecutor() {
        assertThatThrownBy(() -> requestEmailRecoveryCode.request("not-an-address"))
                .isInstanceOf(InvalidRecoveryEmailException.class);

        assertThat(executor.pendingTasks).isEmpty();
    }

    @Test
    void request_withAnUnknownAddress_stillHandsExactlyOneTaskToTheExecutorAndTouchesNothingBeforeItRuns() {
        List<String> callLog = new ArrayList<>();
        RequestEmailRecoveryCode request = requestWithRecordedCollaborators(callLog);

        request.request("nobody@example.test");

        assertThat(executor.pendingTasks).hasSize(1);
        assertThat(callLog).isEmpty();
        executor.runPendingTasks();
        assertThat(callLog).containsExactly("findConfirmedFor");
    }

    @Test
    void request_withOnlyAPendingBinding_stillHandsExactlyOneTaskToTheExecutorAndTouchesNothingBeforeItRuns() {
        bindings.savePending(RecoveryEmailBinding.pending(DIGEST, FIRST_ACCOUNT, RecoveryEmailHint.masking(ADDRESS), NOW));
        List<String> callLog = new ArrayList<>();
        RequestEmailRecoveryCode request = requestWithRecordedCollaborators(callLog);

        request.request(ADDRESS);

        assertThat(executor.pendingTasks).hasSize(1);
        assertThat(callLog).isEmpty();
        executor.runPendingTasks();
        assertThat(callLog).containsExactly("findConfirmedFor");
    }

    @Test
    void request_withAConfirmedBinding_handsExactlyOneTaskToTheExecutorAndTouchesNothingBeforeItRuns() {
        bindConfirmed(FIRST_ACCOUNT);
        List<String> callLog = new ArrayList<>();
        RequestEmailRecoveryCode request = requestWithRecordedCollaborators(callLog);

        request.request(ADDRESS);

        assertThat(executor.pendingTasks).hasSize(1);
        assertThat(callLog).isEmpty();
        executor.runPendingTasks();
        assertThat(callLog).containsExactly("findConfirmedFor", "tryRequest", "store");
    }

    @Test
    void request_whenTheMailFails_keepsTheStoredCodeAndHasUsedUpTheAddressBudget() {
        // Current behavior, pinned: the code is stored and the budget slot taken before the mail goes
        // out, so a failed delivery leaves a valid code behind and a retry within the cooldown is dropped.
        bindConfirmed(FIRST_ACCOUNT);
        InMemoryRecoveryEmailThrottles realThrottles = new InMemoryRecoveryEmailThrottles(Clock.fixed(NOW, ZoneOffset.UTC));
        FailingRecoveryMail failingMail = new FailingRecoveryMail();
        requestWith(failingMail, realThrottles).request(ADDRESS);
        executor.runPendingTasks();

        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER).orElseThrow().codeHash())
                .isEqualTo(hasher.hash(failingMail.attemptedCode));

        requestWith(sendRecoveryCodeEmail, realThrottles).request(ADDRESS);
        executor.runPendingTasks();
        assertThat(sendRecoveryCodeEmail.recoveryMailRecipients).isEmpty();
    }

    @Test
    void request_whenTheIssuanceFails_logsNeitherTheAddressNorTheCode() {
        bindConfirmed(FIRST_ACCOUNT);
        FailingRecoveryMail failingMail = new FailingRecoveryMail();
        requestWith(failingMail, throttles).request(ADDRESS);

        try (CapturedLogs logs = CapturedLogs.ofLoggerOf(RequestEmailRecoveryCode.class)) {
            executor.runPendingTasks();

            assertThat(logs.hasLoggedAnything()).isTrue();
            assertThat(logs.allOutput())
                    .doesNotContain(ADDRESS)
                    .doesNotContain(failingMail.attemptedCode);
        }
    }

    @Test
    void request_whenTheIssuanceFails_doesNotPropagateTheFailureFromTheExecutor() {
        bindConfirmed(FIRST_ACCOUNT);
        requestWith(new FailingRecoveryMail(), throttles).request(ADDRESS);

        assertThatCode(executor::runPendingTasks).doesNotThrowAnyException();
    }

    private RequestEmailRecoveryCode requestWithRecordedCollaborators(List<String> callLog) {
        return new RequestEmailRecoveryCode(
                recordingCalls(RecoveryEmailBindingRepository.class, bindings, callLog),
                recordingCalls(EmailRecoveryCodeStore.class, emailRecoveryCodeStore, callLog),
                hasher,
                sendRecoveryCodeEmail,
                new Sha256RecoveryEmailDigester(),
                recordingCalls(RecoveryRequestThrottle.class, throttles, callLog),
                executor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RequestEmailRecoveryCode requestWith(SendRecoveryCodeEmail mail, RecoveryRequestThrottle throttle) {
        return new RequestEmailRecoveryCode(
                bindings,
                emailRecoveryCodeStore,
                hasher,
                mail,
                new Sha256RecoveryEmailDigester(),
                throttle,
                executor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Fails like a mail server that quotes the recipient and the code in its error message. */
    private static final class FailingRecoveryMail implements SendRecoveryCodeEmail {
        String attemptedCode;

        @Override
        public void sendAttachConfirmationCode(String address, String code) {}

        @Override
        public void sendRecoveryCode(String address, String code) {
            attemptedCode = code;
            throw new IllegalStateException("mail server rejected " + address + " with code " + code);
        }
    }
}
