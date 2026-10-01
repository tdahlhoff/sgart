package de.sgart.identity.application;

import static de.sgart.identity.RecoveryEmailBindingFixtures.saveConfirmedBinding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.application.RecoveryEmailTestSupport.ConfigurableThrottles;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailTestSupport.Sha256RecoveryEmailDigester;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingSendRecoveryCodeEmail;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, in-memory doubles, no framework (CLAUDE.md §6). Proves the attach flow:
 * a pending binding plus a hashed code and a mail, the three separate budgets, and that nothing
 * about the address changes what the caller sees.
 */
class AttachRecoveryEmailTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final String CALLER_ID = "caller-1";
    private static final KeycloakUserId CALLER = new KeycloakUserId(CALLER_ID);
    private static final RecoveryCodeSubject CALLER_CODE_SUBJECT = RecoveryCodeSubject.forAccount(CALLER);
    private static final String ADDRESS = "person@example.test";
    private static final RecoveryEmailDigest ADDRESS_DIGEST = Sha256RecoveryEmailDigester.digestOf(ADDRESS);

    private final InMemoryRecoveryEmailBindingRepository bindings = new InMemoryRecoveryEmailBindingRepository();
    private final InMemoryEmailRecoveryCodeStore codeStore = new InMemoryEmailRecoveryCodeStore();
    private final IdentityRecoveryCodeHasher hasher = new IdentityRecoveryCodeHasher();
    private final RecordingSendRecoveryCodeEmail mails = new RecordingSendRecoveryCodeEmail();
    private final ConfigurableThrottles throttles = new ConfigurableThrottles();
    private final AttachRecoveryEmail attachRecoveryEmail = new AttachRecoveryEmail(
            bindings,
            new Sha256RecoveryEmailDigester(),
            codeStore,
            hasher,
            mails,
            throttles,
            throttles,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void attach_toAFreshAddress_writesAPendingBindingStoresACodeAndMailsIt() {
        attachRecoveryEmail.attach(CALLER_ID, ADDRESS);

        RecoveryEmailBinding pendingBinding = bindings.findPendingFor(CALLER).orElseThrow();
        assertThat(pendingBinding.digest()).isEqualTo(ADDRESS_DIGEST);
        assertThat(pendingBinding.hint()).isEqualTo(RecoveryEmailHint.masking(ADDRESS));
        assertThat(pendingBinding.isConfirmed()).isFalse();
        assertThat(mails.attachMailRecipients).containsExactly(ADDRESS);
        assertThat(codeStore.find(CALLER_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM).orElseThrow().codeHash())
                .isEqualTo(hasher.hash(mails.attachMailCodes.get(0)));
    }

    @Test
    void attachThenConfirm_reattachingTheCallersAlreadyConfirmedAddress_confirmsWithTheMailedCode() {
        ConfirmRecoveryEmail confirmRecoveryEmail =
                new ConfirmRecoveryEmail(bindings, codeStore, hasher, Clock.fixed(NOW, ZoneOffset.UTC));
        attachRecoveryEmail.attach(CALLER_ID, ADDRESS);
        confirmRecoveryEmail.confirm(CALLER_ID, mails.attachMailCodes.get(0));
        attachRecoveryEmail.attach(CALLER_ID, ADDRESS);

        confirmRecoveryEmail.confirm(CALLER_ID, mails.attachMailCodes.get(1));

        assertThat(bindings.all()).extracting(RecoveryEmailBinding::digest).containsExactly(ADDRESS_DIGEST);
        assertThat(bindings.hasConfirmedBindingFor(CALLER)).isTrue();
    }

    @Test
    void attach_toAnAddressConfirmedOnAnotherAccount_stillWritesAPendingBindingAndMailsACode() {
        KeycloakUserId otherAccount = new KeycloakUserId("other-account");
        saveConfirmedBinding(bindings, ADDRESS_DIGEST, otherAccount, RecoveryEmailHint.masking(ADDRESS), NOW);

        attachRecoveryEmail.attach(CALLER_ID, ADDRESS);

        assertThat(bindings.findPendingFor(CALLER)).isPresent();
        assertThat(mails.attachMailRecipients).containsExactly(ADDRESS);
        assertThat(bindings.hasConfirmedBindingFor(otherAccount)).isTrue();
    }

    @Test
    void attachThenConfirm_toAnAddressConfirmedOnAnotherAccount_leavesBothAccountsWithAConfirmedBinding() {
        KeycloakUserId otherAccount = new KeycloakUserId("other-account");
        saveConfirmedBinding(bindings, ADDRESS_DIGEST, otherAccount, RecoveryEmailHint.masking(ADDRESS), NOW);
        ConfirmRecoveryEmail confirmRecoveryEmail =
                new ConfirmRecoveryEmail(bindings, codeStore, hasher, Clock.fixed(NOW, ZoneOffset.UTC));
        attachRecoveryEmail.attach(CALLER_ID, ADDRESS);

        confirmRecoveryEmail.confirm(CALLER_ID, mails.attachMailCodes.get(0));

        assertThat(bindings.findConfirmedFor(ADDRESS_DIGEST))
                .extracting(RecoveryEmailBinding::keycloakUserId)
                .containsExactlyInAnyOrder(CALLER, otherAccount);
    }

    @Test
    void attach_whenTheCallerIsOverBudget_throwsRateLimitedAndChangesNothing() {
        throttles.attachRequestAllowed = false;

        assertThatThrownBy(() -> attachRecoveryEmail.attach(CALLER_ID, ADDRESS))
                .isInstanceOf(RecoveryCodeRateLimitedException.class);

        assertThat(bindings.all()).isEmpty();
        assertThat(codeStore.size()).isZero();
        assertThat(mails.attachMailRecipients).isEmpty();
    }

    @Test
    void attach_whenTheAddressIsOverItsMailBudget_returnsSilentlyAndStoresNothing() {
        throttles.attachMailAllowed = false;

        attachRecoveryEmail.attach(CALLER_ID, ADDRESS);

        assertThat(bindings.all()).isEmpty();
        assertThat(codeStore.size()).isZero();
        assertThat(mails.attachMailRecipients).isEmpty();
    }

    @Test
    void attach_replacesTheCallersEarlierPendingBinding() {
        attachRecoveryEmail.attach(CALLER_ID, "first@example.test");
        String firstCode = mails.attachMailCodes.get(0);

        attachRecoveryEmail.attach(CALLER_ID, "second@example.test");

        assertThat(bindings.all()).hasSize(1);
        assertThat(bindings.findPendingFor(CALLER).orElseThrow().digest())
                .isEqualTo(Sha256RecoveryEmailDigester.digestOf("second@example.test"));
        assertThat(codeStore.find(CALLER_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM).orElseThrow().codeHash())
                .isEqualTo(hasher.hash(mails.attachMailCodes.get(1)))
                .isNotEqualTo(hasher.hash(firstCode));
    }

    @Test
    void attach_neverStoresThePlaintextAddress() {
        attachRecoveryEmail.attach(CALLER_ID, ADDRESS);

        // Redacted toString() would hide a leak, so every stored field value is inspected directly.
        RecoveryEmailBinding pendingBinding = bindings.findPendingFor(CALLER).orElseThrow();
        assertThat(pendingBinding.hint().value()).isEqualTo("p***@e***.test");
        List<String> storedValuesBesidesTheHint = new ArrayList<>(
                List.of(pendingBinding.digest().value(), pendingBinding.keycloakUserId().value()));
        codeStore.all().forEach(row -> storedValuesBesidesTheHint.addAll(
                List.of(row.subject().value(), row.codeHash(), row.purpose().name())));
        assertThat(storedValuesBesidesTheHint)
                .noneMatch(value -> value.contains(ADDRESS))
                .noneMatch(value -> value.contains("person"))
                .noneMatch(value -> value.contains("example.test"));
    }

    @Test
    void attach_normalizesTheAddressBeforeDigestingAndMailing() {
        attachRecoveryEmail.attach(CALLER_ID, "  Person@Example.TEST ");

        assertThat(bindings.findPendingFor(CALLER).orElseThrow().digest()).isEqualTo(ADDRESS_DIGEST);
        assertThat(mails.attachMailRecipients).containsExactly(ADDRESS);
    }

    @Test
    void attach_withAMalformedAddress_isRejectedBeforeAnySideEffect() {
        assertThatThrownBy(() -> attachRecoveryEmail.attach(CALLER_ID, "not-an-email"))
                .isInstanceOf(InvalidRecoveryEmailException.class);

        assertThat(bindings.all()).isEmpty();
        assertThat(mails.attachMailRecipients).isEmpty();
    }
}
