package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.application.RecoveryEmailTestSupport.CapturingExecutor;
import de.sgart.identity.application.RecoveryEmailTestSupport.ConfigurableThrottles;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingSendRecoveryCodeEmail;
import de.sgart.identity.application.RecoveryEmailTestSupport.Sha256RecoveryEmailDigester;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
        bindings.savePending(RecoveryEmailBinding.pending(DIGEST, account, RecoveryEmailHint.masking(ADDRESS), NOW));
        bindings.confirm(bindings.findPendingFor(account).orElseThrow().confirm(NOW));
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
    void request_whenTheIssuanceFails_doesNotPropagateTheFailureFromTheExecutor() {
        bindConfirmed(FIRST_ACCOUNT);
        RequestEmailRecoveryCode failingRequest = new RequestEmailRecoveryCode(
                bindings,
                emailRecoveryCodeStore,
                hasher,
                new SendRecoveryCodeEmail() {
                    @Override
                    public void sendAttachConfirmationCode(String address, String code) {}

                    @Override
                    public void sendRecoveryCode(String address, String code) {
                        throw new IllegalStateException("mail server down");
                    }
                },
                new Sha256RecoveryEmailDigester(),
                throttles,
                executor,
                Clock.fixed(NOW, ZoneOffset.UTC));

        failingRequest.request(ADDRESS);

        executor.runPendingTasks();
    }
}
