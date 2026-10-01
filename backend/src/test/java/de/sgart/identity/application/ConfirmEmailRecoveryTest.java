package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.adapter.out.InMemoryMembershipNicknameRepository;
import de.sgart.identity.adapter.out.InMemoryProvisionedAccountRepository;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.application.RecoveryEmailTestSupport.FailingRebindAccountCredential;
import de.sgart.identity.application.RecoveryEmailTestSupport.ConfigurableThrottles;
import de.sgart.identity.application.RecoveryEmailTestSupport.FakeFindHouseholdNames;
import de.sgart.identity.application.RecoveryEmailTestSupport.FakeGetAccountDetails;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailTestSupport.OrderedDeleteAccount;
import de.sgart.identity.application.RecoveryEmailTestSupport.OrderedRebindAccountCredential;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingCreateAccount;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingDeleteAccount;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingRebindAccountCredential;
import de.sgart.identity.application.RecoveryEmailTestSupport.Sha256RecoveryEmailDigester;
import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MembershipNickname;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBinding;
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
    private final RecordingCreateAccount createAccount = new RecordingCreateAccount();
    private final MutableClock clock = new MutableClock(NOW);
    private final ConfirmEmailRecovery confirmEmailRecovery = new ConfirmEmailRecovery(
            bindings,
            new Sha256RecoveryEmailDigester(),
            throttles,
            candidateResolver,
            emailRecoveryCodeStore,
            hasher,
            getAccountDetails,
            deleteAccount,
            provisionedAccountRepository,
            rebindAccountCredential,
            createAccount,
            clock);

    @Test
    void confirmEmailRecovery_deletesThrowawayThenRebindsTargetUsernameAndPublicKey() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE);

        assertThat(deleteAccount.deletionOrder).containsExactly(THROWAWAY);
        assertThat(provisionedAccountRepository.contains(THROWAWAY)).isFalse();
        assertThat(rebindAccountCredential.rebinds).hasSize(1);
        var rebind = rebindAccountCredential.rebinds.get(0);
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

        assertThat(rebindAccountCredential.rebinds.get(0).keycloakUserId()).isEqualTo(TARGET);
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

        assertThat(rebindAccountCredential.rebinds).hasSize(1);
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
        assertThat(rebindAccountCredential.rebinds).hasSize(1);
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
    void confirmEmailRecovery_deletesTheThrowawayBeforeRebindingTheTarget() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));

        List<String> sharedLog = new ArrayList<>();
        ConfirmEmailRecovery orderedConfirmEmailRecovery = new ConfirmEmailRecovery(
                bindings,
            new Sha256RecoveryEmailDigester(),
            throttles,
            candidateResolver,
            emailRecoveryCodeStore,
                hasher,
                getAccountDetails,
                new OrderedDeleteAccount(sharedLog),
                provisionedAccountRepository,
                new OrderedRebindAccountCredential(sharedLog),
                createAccount,
                clock);

        orderedConfirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE);

        assertThat(sharedLog).containsExactly("delete:" + THROWAWAY_ID, "rebind:target-A1");
    }

    private ConfirmEmailRecovery confirmEmailRecoveryWithFailingRebind() {
        return new ConfirmEmailRecovery(
                bindings,
            new Sha256RecoveryEmailDigester(),
            throttles,
            candidateResolver,
            emailRecoveryCodeStore,
                hasher,
                getAccountDetails,
                deleteAccount,
                provisionedAccountRepository,
                new FailingRebindAccountCredential(),
                createAccount,
                clock);
    }

    private void seedRecoverableAccount() {
        bindConfirmed(TARGET, "person@example.test");
        emailRecoveryCodeStore.store(CODE_SUBJECT, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));
    }

    @Test
    void confirmEmailRecovery_whenTheRebindFails_restoresTheThrowawayAndKeepsTheCodeForARetry() {
        seedRecoverableAccount();

        assertThatThrownBy(() ->
                        confirmEmailRecoveryWithFailingRebind().confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE))
                .isInstanceOf(RecoveryRebindFailedException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(deleteAccount.deletedIds).containsExactly(THROWAWAY);
        assertThat(createAccount.creations).containsExactly(new RecordingCreateAccount.Creation("U2", "K2"));
        assertThat(provisionedAccountRepository.contains(THROWAWAY)).isFalse();
        assertThat(provisionedAccountRepository.contains(new KeycloakUserId("restored-U2"))).isTrue();
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isPresent();
    }

    @Test
    void confirmEmailRecovery_whenTheRebindAndTheRestoreBothFail_stillRaisesTheCleanRetryableFailure() {
        seedRecoverableAccount();
        createAccount.shouldFail = true;

        assertThatThrownBy(() ->
                        confirmEmailRecoveryWithFailingRebind().confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE))
                .isInstanceOf(RecoveryRebindFailedException.class)
                .satisfies(failure -> assertThat(failure.getCause().getSuppressed()).hasSize(1));

        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isPresent();
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

    @Test
    void confirmEmailRecovery_whenTheRebindWasAppliedButItsResponseWasLost_completesTheRecoveryInsteadOfFailing() {
        seedRecoverableAccount();
        createAccount.existingHolder = TARGET;

        confirmEmailRecoveryWithFailingRebind().confirmAndRebind(THROWAWAY_ID, "person@example.test", "042817", NO_CHOICE);

        assertThat(provisionedAccountRepository.contains(TARGET)).isFalse();
        assertThat(emailRecoveryCodeStore.find(CODE_SUBJECT, RecoveryCodePurpose.RECOVER)).isEmpty();
    }

    private static final KeycloakUserId OTHER_TARGET = new KeycloakUserId("target-B1");

    private void bindConfirmed(KeycloakUserId account, String address) {
        bindings.savePending(RecoveryEmailBinding.pending(
                Sha256RecoveryEmailDigester.digestOf(address), account, RecoveryEmailHint.masking(address), NOW));
        bindings.confirm(bindings.findPendingFor(account).orElseThrow().confirm(NOW));
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
        assertThat(rebindAccountCredential.rebinds.get(0).keycloakUserId()).isEqualTo(TARGET);
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
        assertThat(rebindAccountCredential.rebinds).hasSize(1);
        assertThat(rebindAccountCredential.rebinds.get(0).keycloakUserId()).isEqualTo(OTHER_TARGET);
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
        assertThat(rebindAccountCredential.rebinds.get(0).keycloakUserId()).isEqualTo(TARGET);
    }

    @Test
    void confirm_afterASuccessfulRebind_resetsTheAddressRecoveryBudget() {
        seedRecoverableAccount();

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(throttles.resetDigests).containsExactly(ADDRESS_DIGEST);
    }

    @Test
    void confirm_whenTheRebindFails_doesNotResetTheAddressRecoveryBudget() {
        seedRecoverableAccount();

        assertThatThrownBy(() -> confirmEmailRecoveryWithFailingRebind()
                        .confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE))
                .isInstanceOf(RecoveryRebindFailedException.class);

        assertThat(throttles.resetDigests).isEmpty();
    }

    @Test
    void confirm_afterASuccessfulRebind_removesTheThrowawaysBindingsAndCodes() {
        seedRecoverableAccount();
        bindConfirmed(THROWAWAY, "throwaway@example.test");
        emailRecoveryCodeStore.store(
                RecoveryCodeSubject.forAccount(THROWAWAY), RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW.plusSeconds(60), NOW);

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(bindings.findAllFor(THROWAWAY)).isEmpty();
        assertThat(emailRecoveryCodeStore.find(
                        RecoveryCodeSubject.forAccount(THROWAWAY), RecoveryCodePurpose.ATTACH_CONFIRM))
                .isEmpty();
        assertThat(bindings.findConfirmedFor(TARGET)).isPresent();
    }

    @Test
    void confirm_withADifferentlyCasedAddress_rebindsOntoTheCandidate() {
        seedRecoverableAccount();

        EmailRecoveryOutcome outcome =
                confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "  Person@Example.TEST ", "042817", NO_CHOICE);

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.Rebound.class);
        assertThat(rebindAccountCredential.rebinds.get(0).keycloakUserId()).isEqualTo(TARGET);
    }

    @Test
    void confirm_whenEveryCleanupStepFailsAfterTheRebind_stillReturnsRebound() {
        seedRecoverableAccount();
        ConfirmEmailRecovery withFailingCleanup = new ConfirmEmailRecovery(
                RecoveryEmailTestSupport.failingOn(RecoveryEmailBindingRepository.class, bindings, "deleteAllFor"),
                new Sha256RecoveryEmailDigester(),
                new FailingResetThrottles(),
                candidateResolver,
                RecoveryEmailTestSupport.failingOn(
                        EmailRecoveryCodeStore.class, emailRecoveryCodeStore, "delete", "deleteAll"),
                hasher,
                getAccountDetails,
                deleteAccount,
                provisionedAccountRepository,
                rebindAccountCredential,
                createAccount,
                clock);

        EmailRecoveryOutcome outcome =
                withFailingCleanup.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(outcome).isInstanceOf(EmailRecoveryOutcome.Rebound.class);
        assertThat(rebindAccountCredential.rebinds).hasSize(1);
    }

    @Test
    void confirm_whenOnlyTheCodeDeletionFailsAfterTheRebind_theRemainingCleanupStepsStillRun() {
        seedRecoverableAccount();
        bindConfirmed(THROWAWAY, "throwaway@example.test");
        ConfirmEmailRecovery withFailingCodeDeletion = new ConfirmEmailRecovery(
                bindings,
                new Sha256RecoveryEmailDigester(),
                throttles,
                candidateResolver,
                RecoveryEmailTestSupport.failingOn(
                        EmailRecoveryCodeStore.class, emailRecoveryCodeStore, "delete", "deleteAll"),
                hasher,
                getAccountDetails,
                deleteAccount,
                provisionedAccountRepository,
                rebindAccountCredential,
                createAccount,
                clock);

        withFailingCodeDeletion.confirmAndRebind(THROWAWAY_ID, ADDRESS, "042817", NO_CHOICE);

        assertThat(throttles.resetDigests).containsExactly(ADDRESS_DIGEST);
        assertThat(bindings.findAllFor(THROWAWAY)).isEmpty();
    }

    private static final class FailingResetThrottles implements RecoveryRequestThrottle {
        @Override
        public boolean tryRequest(RecoveryEmailDigest digest) {
            return true;
        }

        @Override
        public void reset(RecoveryEmailDigest digest) {
            throw new IllegalStateException("throttle unavailable");
        }
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
