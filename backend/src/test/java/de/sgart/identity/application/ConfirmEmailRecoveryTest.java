package de.sgart.identity.application;

import static de.sgart.identity.RecoveryEmailBindingFixtures.saveConfirmedBinding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.adapter.out.InMemoryMembershipNicknameRepository;
import de.sgart.identity.adapter.out.InMemoryProvisionedAccountRepository;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.CapturedLogs;
import de.sgart.identity.application.RecoveryEmailTestSupport.InMemoryKeycloakAccounts;
import de.sgart.identity.application.RecoveryEmailTestSupport.InjectedFailures;
import de.sgart.identity.application.RecoveryEmailTestSupport.ConfigurableThrottles;
import de.sgart.identity.application.RecoveryEmailTestSupport.FakeFindHouseholdNames;
import de.sgart.identity.application.RecoveryEmailTestSupport.FakeGetAccountDetails;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailTestSupport.OrderedDeleteAccount;
import de.sgart.identity.application.RecoveryEmailTestSupport.OrderedRebindAccountCredential;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingDeleteAccount;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingRebindAccountCredential;
import de.sgart.identity.application.RecoveryEmailTestSupport.Sha256RecoveryEmailDigester;
import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MembershipNickname;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — proves Story 7.3, AC2/design §1.1: the R1 rebind deletes the throwaway account
 * then rebinds the target's username/publicKey, in that order, preserving the target's stable
 * {@link KeycloakUserId}; an unknown email is rejected the same way a wrong code is (no
 * enumeration, D-H).
 */
class ConfirmEmailRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final KeycloakUserId TARGET = new KeycloakUserId("target-A1");
    private static final String THROWAWAY_ID = "throwaway-A2";
    private static final KeycloakUserId THROWAWAY = new KeycloakUserId(THROWAWAY_ID);

    private static final String ADDRESS = "person@example.test";
    private static final RecoveryEmailDigest ADDRESS_DIGEST = Sha256RecoveryEmailDigester.digestOf(ADDRESS);
    private static final RecoveryCodeSubject CODE_SUBJECT = RecoveryCodeSubject.forAddress(ADDRESS_DIGEST);
    private static final String NO_CHOICE = null;

    private final InMemoryRecoveryEmailBindingRepository bindings = new InMemoryRecoveryEmailBindingRepository();
    private final ConfigurableThrottles throttles = new ConfigurableThrottles();
    private final InMemoryMemberMappingRepository memberMappings = new InMemoryMemberMappingRepository();
    private final InMemoryMembershipNicknameRepository nicknames = new InMemoryMembershipNicknameRepository(memberMappings);
    private final FakeFindHouseholdNames householdNames = new FakeFindHouseholdNames();
    private final ResolveRecoveryCandidates candidateResolver =
            new ResolveRecoveryCandidates(memberMappings, nicknames, householdNames);
    private final InMemoryEmailRecoveryCodeStore emailRecoveryCodeStore = new InMemoryEmailRecoveryCodeStore();
    private final IdentityRecoveryCodeHasher hasher = new IdentityRecoveryCodeHasher();
    private final FakeGetAccountDetails getAccountDetails = new FakeGetAccountDetails();
    private final RecordingDeleteAccount deleteAccount = new RecordingDeleteAccount();
    private final InMemoryProvisionedAccountRepository provisionedAccountRepository =
            new InMemoryProvisionedAccountRepository();
    private final RecordingRebindAccountCredential rebindAccountCredential = new RecordingRebindAccountCredential();
    private final MutableClock clock = new MutableClock(NOW);
    private final ConfirmEmailRecovery confirmEmailRecovery =
            confirmEmailRecoveryUsing(bindings, throttles, emailRecoveryCodeStore, deleteAccount, rebindAccountCredential);

    /** The one place that spells out the collaborators; a test swaps only the double it wants to break. */
    private ConfirmEmailRecovery confirmEmailRecoveryUsing(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            RecoveryRequestThrottle recoveryRequestThrottle,
            EmailRecoveryCodeStore codeStore,
            DeleteAccount accountDeletion,
            RebindAccountCredential credentialRebind) {
        return confirmEmailRecoveryUsing(
                recoveryEmailBindingRepository,
                recoveryRequestThrottle,
                codeStore,
                getAccountDetails,
                accountDeletion,
                credentialRebind);
    }

    private ConfirmEmailRecovery confirmEmailRecoveryOver(InMemoryKeycloakAccounts keycloakAccounts) {
        return confirmEmailRecoveryUsing(
                bindings, throttles, emailRecoveryCodeStore, keycloakAccounts, keycloakAccounts, keycloakAccounts);
    }

    private ConfirmEmailRecovery confirmEmailRecoveryUsing(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            RecoveryRequestThrottle recoveryRequestThrottle,
            EmailRecoveryCodeStore codeStore,
            GetAccountDetails accountDetailsLookup,
            DeleteAccount accountDeletion,
            RebindAccountCredential credentialRebind) {
        return new ConfirmEmailRecovery(
                recoveryEmailBindingRepository,
                new Sha256RecoveryEmailDigester(),
                recoveryRequestThrottle,
                candidateResolver,
                codeStore,
                hasher,
                accountDetailsLookup,
                accountDeletion,
                provisionedAccountRepository,
                credentialRebind,
                clock);
    }

    @Test
    void confirmEmailRecovery_parksTheThrowawayThenRebindsTargetUsernameAndPublicKeyThenDeletesTheThrowaway() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE);

        assertThat(deleteAccount.deletionOrder).containsExactly(THROWAWAY);
        assertThat(provisionedAccountRepository.contains(THROWAWAY)).isFalse();
        assertThat(rebindAccountCredential.rebinds).hasSize(2);
        var parking = rebindAccountCredential.rebinds.get(0);
        assertThat(parking.keycloakUserId()).isEqualTo(THROWAWAY);
        assertThat(parking.username()).isNotEqualTo("U2");
        assertThat(parking.publicKey()).isEqualTo("K2");
        var rebind = targetRebinds().get(0);
        assertThat(rebind.keycloakUserId()).isEqualTo(TARGET);
        assertThat(rebind.username()).isEqualTo("U2");
        assertThat(rebind.publicKey()).isEqualTo("K2");
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isEmpty();
    }

    @Test
    void confirmEmailRecovery_preservesTheTargetKeycloakUserId() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE);

        assertThat(targetRebinds().get(0).keycloakUserId()).isEqualTo(TARGET);
        assertThat(deleteAccount.deletedIds).doesNotContain(TARGET);
    }

    @Test
    void confirmEmailRecovery_withUnknownEmail_isRejectedTheSameWayAsAWrongCode() {
        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "nobody@example.test", "042817", NO_CHOICE))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmEmailRecovery_withWrongCode_isRejectedAndChangesNothing() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "000000", NO_CHOICE))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmEmailRecovery_withBlankCode_isRejectedFastWithoutHashingNull() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", " ", NO_CHOICE))
                .isInstanceOf(RecoveryCodeRejectedException.class);
        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", null, NO_CHOICE))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmEmailRecovery_exhaustedAttempts_isRejected() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        for (int i = 0; i < RecoveryCode.MAX_ATTEMPTS; i++) {
            emailRecoveryCodeStore.incrementAttempts(CODE_SUBJECT, RecoveryCodePurpose.RECOVER);
        }

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmEmailRecovery_withExpiredCode_isRejectedAndChangesNothing() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        // Registered so a regression that skips the expiry check would actually reach
        // delete/rebind instead of failing earlier on a missing throwaway lookup — otherwise the
        // "no delete/rebind" asserts below would pass vacuously.
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));
        clock.advance(Duration.ofSeconds(61));

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
        var stored = emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER);
        assertThat(stored).isPresent();
        // Pins the real (correct) production behavior: an expired candidate still counts as a
        // wrong guess and increments the attempt counter — the row is not consumed, but not untouched either.
        assertThat(stored.get().attempts()).isEqualTo(1);
    }

    @Test
    void confirmEmailRecovery_atExactlyTheExpiryInstant_isStillAccepted() {
        // RecoveryCode.matches uses isAfter(expiresAt), so the boundary instant itself must still
        // be treated as unexpired — an off-by-one (isAfter → !isBefore, or >=) would flip this.
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(
                CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plus(RecoveryCode.TTL), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));
        clock.advance(RecoveryCode.TTL);

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE);

        assertThat(targetRebinds()).hasSize(1);
    }

    @Test
    void confirmEmailRecovery_replayAfterSuccess_isRejectedBecauseTheCodeWasConsumed() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE);

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE))
                .isInstanceOf(RecoveryCodeRejectedException.class);
        assertThat(targetRebinds()).hasSize(1);
    }

    @Test
    void confirmEmailRecovery_targetIsTheCallersOwnThrowaway_isRejectedAndChangesNothing() {
        // The caller's own still-throwaway account resolving as the recovery target must never
        // reach delete-then-rebind: deleting it would delete the target too (Story 7.3 review
        // finding).
        bindConfirmed(THROWAWAY, ADDRESS);
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmEmailRecovery_freesTheUsernameByParkingTheThrowawayAndDeletesItOnlyAfterTheRebindSucceeded() {
        seedRecoverableAccount();

        List<String> sharedLog = new ArrayList<>();
        ConfirmEmailRecovery orderedConfirmEmailRecovery = confirmEmailRecoveryUsing(
                bindings,
                throttles,
                emailRecoveryCodeStore,
                new OrderedDeleteAccount(sharedLog),
                new OrderedRebindAccountCredential(sharedLog));

        orderedConfirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE);

        assertThat(sharedLog)
                .containsExactly("rebind:" + THROWAWAY_ID, "rebind:target-A1", "delete:" + THROWAWAY_ID);
    }

    private void seedRecoverableAccount() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));
    }

    private InMemoryKeycloakAccounts seedKeycloakAccountsForRecovery() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));
        InMemoryKeycloakAccounts keycloakAccounts = new InMemoryKeycloakAccounts();
        keycloakAccounts.register(THROWAWAY, "U2", "K2");
        keycloakAccounts.register(TARGET, "U1", "K1");
        return keycloakAccounts;
    }

    @Test
    void confirmEmailRecovery_whenTheRebindFails_keepsTheCallersAccountIdAndItsHouseholdMembership() {
        InMemoryKeycloakAccounts keycloakAccounts = seedKeycloakAccountsForRecovery();
        HouseholdId householdCreatedByTheThrowaway = HouseholdId.generate();
        joinHousehold(THROWAWAY, householdCreatedByTheThrowaway);
        keycloakAccounts.failRebindsOf(TARGET, 0);

        assertThatThrownBy(() -> confirmEmailRecoveryOver(keycloakAccounts)
                        .confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE))
                .isInstanceOf(RecoveryRebindFailedException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(keycloakAccounts.deletedAccounts).isEmpty();
        assertThat(keycloakAccounts.usernameOf(THROWAWAY)).isEqualTo("U2");
        assertThat(memberMappings.householdIdsFor(THROWAWAY)).containsExactly(householdCreatedByTheThrowaway);
        assertThat(provisionedAccountRepository.contains(THROWAWAY)).isTrue();
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isPresent();
    }

    @Test
    void confirmEmailRecovery_whenTheRebindAndTheUnparkingBothFail_failsWithoutEverCreatingAnotherAccount() {
        InMemoryKeycloakAccounts keycloakAccounts = seedKeycloakAccountsForRecovery();
        keycloakAccounts.failRebindsOf(TARGET, 0);
        keycloakAccounts.failRebindsOf(THROWAWAY, 1);

        assertThatThrownBy(() -> confirmEmailRecoveryOver(keycloakAccounts)
                        .confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE))
                .isInstanceOf(RecoveryThrowawayRestoreFailedException.class)
                .satisfies(failure -> assertThat(failure.getCause().getSuppressed()).hasSize(1));

        assertThat(keycloakAccounts.deletedAccounts).isEmpty();
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isPresent();
    }

    @Test
    void confirmEmailRecovery_whenParkingTheThrowawayFails_failsRetryablyBecauseNothingChanged() {
        InMemoryKeycloakAccounts keycloakAccounts = seedKeycloakAccountsForRecovery();
        keycloakAccounts.failRebindsOf(THROWAWAY, 0);

        assertThatThrownBy(() -> confirmEmailRecoveryOver(keycloakAccounts)
                        .confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE))
                .isInstanceOf(RecoveryRebindFailedException.class);

        assertThat(keycloakAccounts.usernameOf(THROWAWAY)).isEqualTo("U2");
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isPresent();
    }

    @Test
    void confirmEmailRecovery_whenTheRebindWasAppliedButItsResponseWasLost_completesTheRecoveryInsteadOfFailing() {
        InMemoryKeycloakAccounts keycloakAccounts = seedKeycloakAccountsForRecovery();
        keycloakAccounts.applyRebindOfThenFail(TARGET);

        confirmEmailRecoveryOver(keycloakAccounts)
                .confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE);

        assertThat(keycloakAccounts.usernameOf(TARGET)).isEqualTo("U2");
        assertThat(keycloakAccounts.exists(THROWAWAY)).isFalse();
        assertThat(provisionedAccountRepository.contains(THROWAWAY)).isFalse();
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isEmpty();
    }

    @Test
    void confirmEmailRecovery_whenTheCallersAccountNoLongerExists_isRejectedAsUnauthorizedAndChangesNothing() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE))
                .isInstanceOf(CallerAccountNotFoundException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isPresent();
    }

    private List<RecordingRebindAccountCredential.Rebind> targetRebinds() {
        return rebindAccountCredential.rebinds.stream()
                .filter(rebind -> !rebind.keycloakUserId().equals(THROWAWAY))
                .toList();
    }

    private static final KeycloakUserId OTHER_TARGET = new KeycloakUserId("target-B1");

    private void bindConfirmed(KeycloakUserId account, String address) {
        saveConfirmedBinding(
                bindings, Sha256RecoveryEmailDigester.digestOf(address), account, RecoveryEmailHint.masking(address), NOW);
    }

    private void joinHousehold(KeycloakUserId account, HouseholdId householdId) {
        memberMappings.seed(new MemberMapping(householdId, MemberId.generate(), account));
    }

    private void seedTwoCandidateAccounts() {
        bindConfirmed(TARGET, ADDRESS);
        bindConfirmed(OTHER_TARGET, ADDRESS);
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));
    }

    @Test
    void confirm_withOneCandidate_rebindsOntoIt() {
        seedRecoverableAccount();

        EmailRecoveryOutcome outcome =
                confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.Rebound.class);
        assertThat(targetRebinds().get(0).keycloakUserId()).isEqualTo(TARGET);
    }

    @Test
    void confirm_withTwoCandidatesAndNoChoice_returnsCandidatesSortedByHouseholdCountAndKeepsTheCode() {
        seedTwoCandidateAccounts();
        joinHousehold(TARGET, HouseholdId.generate());
        joinHousehold(OTHER_TARGET, HouseholdId.generate());
        joinHousehold(OTHER_TARGET, HouseholdId.generate());

        EmailRecoveryOutcome outcome =
                confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.ChooseAccount.class);
        var candidates = ((EmailRecoveryOutcome.ChooseAccount) outcome).candidates();
        assertThat(candidates).extracting(RecoveryCandidate::accountId).containsExactly("target-B1", "target-A1");
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isPresent();
        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirm_withAChosenCandidate_rebindsOntoIt() {
        seedTwoCandidateAccounts();

        EmailRecoveryOutcome outcome =
                confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", OTHER_TARGET.value());

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.Rebound.class);
        assertThat(targetRebinds()).hasSize(1);
        assertThat(targetRebinds().get(0).keycloakUserId()).isEqualTo(OTHER_TARGET);
    }

    @Test
    void confirm_withAChoiceOutsideTheCandidates_isRejectedLikeAWrongCode() {
        seedTwoCandidateAccounts();

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", "stranger"))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirm_excludesTheCallersOwnThrowawayFromTheCandidates() {
        seedRecoverableAccount();
        bindConfirmed(THROWAWAY, ADDRESS);

        EmailRecoveryOutcome outcome =
                confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.Rebound.class);
        assertThat(targetRebinds().get(0).keycloakUserId()).isEqualTo(TARGET);
    }

    @Test
    void confirm_afterASuccessfulRebind_resetsTheAddressRecoveryBudget() {
        seedRecoverableAccount();

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(throttles.resetDigests).containsExactly(ADDRESS_DIGEST);
    }

    @Test
    void confirm_whenTheRebindFails_doesNotResetTheAddressRecoveryBudget() {
        InMemoryKeycloakAccounts keycloakAccounts = seedKeycloakAccountsForRecovery();
        keycloakAccounts.failRebindsOf(TARGET, 0);

        assertThatThrownBy(() -> confirmEmailRecoveryOver(keycloakAccounts)
                        .confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE))
                .isInstanceOf(RecoveryRebindFailedException.class);

        assertThat(throttles.resetDigests).isEmpty();
    }

    @Test
    void confirm_afterASuccessfulRebind_removesTheThrowawaysBindingsAndCodes() {
        seedRecoverableAccount();
        bindConfirmed(THROWAWAY, "throwaway@example.test");
        RecoveryCodeSubject throwawayCodeSubject = RecoveryCodeSubject.forAccount(THROWAWAY);
        emailRecoveryCodeStore.store(
                throwawayCodeSubject, RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW.plusSeconds(60), NOW);

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(bindings.findAllFor(THROWAWAY)).isEmpty();
        assertThat(emailRecoveryCodeStore.find(throwawayCodeSubject, RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
        assertThat(bindings.findConfirmedFor(TARGET)).isPresent();
    }

    @Test
    void confirm_withADifferentlyCasedAddress_rebindsOntoTheCandidate() {
        seedRecoverableAccount();

        EmailRecoveryOutcome outcome =
                confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "  Person@Example.TEST ", "042817", NO_CHOICE);

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.Rebound.class);
        assertThat(targetRebinds().get(0).keycloakUserId()).isEqualTo(TARGET);
    }

    @Test
    void confirm_whenEveryCleanupStepFailsAfterTheRebind_stillReturnsRebound() {
        seedRecoverableAccount();
        InjectedFailures injectedFailures = new InjectedFailures();
        ConfirmEmailRecovery withFailingCleanup = confirmEmailRecoveryUsing(
                RecoveryEmailTestSupport.failingOn(
                        RecoveryEmailBindingRepository.class, bindings, injectedFailures, "deleteAllFor"),
                new FailingResetThrottles(),
                RecoveryEmailTestSupport.failingOn(
                        EmailRecoveryCodeStore.class, emailRecoveryCodeStore, injectedFailures, "delete", "deleteAll"),
                deleteAccount,
                rebindAccountCredential);

        EmailRecoveryOutcome outcome =
                withFailingCleanup.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.Rebound.class);
        assertThat(targetRebinds()).hasSize(1);
        assertThat(injectedFailures.firedMethodNames()).containsExactlyInAnyOrder("delete", "deleteAll", "deleteAllFor");
    }

    @Test
    void confirm_whenOnlyTheCodeDeletionFailsAfterTheRebind_theRemainingCleanupStepsStillRun() {
        seedRecoverableAccount();
        bindConfirmed(THROWAWAY, "throwaway@example.test");
        InjectedFailures injectedFailures = new InjectedFailures();
        ConfirmEmailRecovery withFailingCodeDeletion = confirmEmailRecoveryUsing(
                bindings,
                throttles,
                RecoveryEmailTestSupport.failingOn(
                        EmailRecoveryCodeStore.class, emailRecoveryCodeStore, injectedFailures, "delete", "deleteAll"),
                deleteAccount,
                rebindAccountCredential);

        withFailingCodeDeletion.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(injectedFailures.firedMethodNames()).containsExactlyInAnyOrder("delete", "deleteAll");
        assertThat(throttles.resetDigests).containsExactly(ADDRESS_DIGEST);
        assertThat(bindings.findAllFor(THROWAWAY)).isEmpty();
    }

    @Test
    void confirm_whenCleanupFailsAfterTheRebind_logsNeitherTheAddressNorTheCode() {
        seedRecoverableAccount();
        ConfirmEmailRecovery withLeakyFailingCleanup = confirmEmailRecoveryUsing(
                bindings,
                new FailingResetThrottles(),
                emailRecoveryCodeStore,
                deleteAccount,
                rebindAccountCredential);

        try (CapturedLogs logs = CapturedLogs.ofLoggerOf(ConfirmEmailRecovery.class)) {
            withLeakyFailingCleanup.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

            assertThat(logs.hasLoggedAnything()).isTrue();
            assertThat(logs.allOutput()).doesNotContain(ADDRESS).doesNotContain("042817");
        }
    }

    private static final class FailingResetThrottles implements RecoveryRequestThrottle {
        @Override
        public boolean tryRequest(RecoveryEmailDigest digest) {
            return true;
        }

        @Override
        public void reset(RecoveryEmailDigest digest) {
            throw new IllegalStateException("throttle unavailable for " + ADDRESS + " with code 042817");
        }
    }

    @Test
    void confirm_withAWrongCodeAndSeveralCandidatesAndNoChoice_isRejectedWithoutRevealingTheCandidates() {
        seedTwoCandidateAccounts();

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "000000", NO_CHOICE))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER).orElseThrow().attempts())
                .isEqualTo(1);
    }

    @Test
    void confirm_withAWrongCodeAndAChosenCandidate_isRejectedAndRebindsNothing() {
        seedTwoCandidateAccounts();

        assertThatThrownBy(() ->
                        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "000000", OTHER_TARGET.value()))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirm_withAnExpiredCodeAndAChosenCandidate_isRejectedAndRebindsNothing() {
        seedTwoCandidateAccounts();
        clock.advance(Duration.ofSeconds(61));

        assertThatThrownBy(() ->
                        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", OTHER_TARGET.value()))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirm_withTheCallersOwnThrowawayChosenAmongSeveralCandidates_isRejectedAndRebindsNothing() {
        seedTwoCandidateAccounts();
        bindConfirmed(THROWAWAY, ADDRESS);

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", THROWAWAY_ID))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirm_withTheCallersOwnThrowawayChosenBesidesASingleCandidate_isRejectedAndRebindsNothing() {
        seedRecoverableAccount();
        bindConfirmed(THROWAWAY, ADDRESS);

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", THROWAWAY_ID))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirm_withExactlyOneCandidateAndThatCandidateChosen_rebindsOntoIt() {
        seedRecoverableAccount();

        EmailRecoveryOutcome outcome =
                confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", TARGET.value());

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.Rebound.class);
        assertThat(targetRebinds().get(0).keycloakUserId()).isEqualTo(TARGET);
    }

    @Test
    void confirm_withExactlyOneCandidateAndAStrangerChosen_isRejectedAndRebindsNothing() {
        seedRecoverableAccount();

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", "stranger"))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirm_afterAWrongCodeWithAChosenCandidate_countsTheWrongGuessAndStillAcceptsTheRightCode() {
        seedTwoCandidateAccounts();
        assertThatThrownBy(() ->
                        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "000000", OTHER_TARGET.value()))
                .isInstanceOf(RecoveryCodeRejectedException.class);
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER).orElseThrow().attempts())
                .isEqualTo(1);

        EmailRecoveryOutcome outcome =
                confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", OTHER_TARGET.value());

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.Rebound.class);
        assertThat(targetRebinds().get(0).keycloakUserId()).isEqualTo(OTHER_TARGET);
    }

    @Test
    void confirm_afterAStrangerWasChosenWithTheRightCode_keepsTheCodeUsableAndDoesNotCountAGuess() {
        // Current behavior, pinned: the code itself was right, so the verification counts no wrong
        // guess; only the choice is refused. The same code then still completes the recovery.
        seedTwoCandidateAccounts();
        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", "stranger"))
                .isInstanceOf(RecoveryCodeRejectedException.class);
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER).orElseThrow().attempts())
                .isZero();

        EmailRecoveryOutcome outcome =
                confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", TARGET.value());

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.Rebound.class);
    }

    @Test
    void candidates_showHouseholdNamesAndTheCandidatesNicknames() {
        seedTwoCandidateAccounts();
        HouseholdId flat = HouseholdId.generate();
        HouseholdId notYetProjected = HouseholdId.generate();
        joinHousehold(TARGET, flat);
        joinHousehold(TARGET, notYetProjected);
        householdNames.register(flat, "Test Flat");
        nicknames.save(new MembershipNickname(TARGET, flat, "Tester"));

        EmailRecoveryOutcome outcome =
                confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        var candidates = ((EmailRecoveryOutcome.ChooseAccount) outcome).candidates();
        RecoveryCandidate withHouseholds = candidates.get(0);
        assertThat(withHouseholds.accountId()).isEqualTo(TARGET.value());
        assertThat(withHouseholds.households())
                .containsExactlyInAnyOrder(
                        new RecoveryCandidate.Household("Test Flat", "Tester"),
                        new RecoveryCandidate.Household("", ""));
        assertThat(candidates.get(1).households()).isEmpty();
    }

    /** Lets {@code confirmEmailRecovery_withExpiredCode_isRejectedAndChangesNothing} move past a TTL without a sleep. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
