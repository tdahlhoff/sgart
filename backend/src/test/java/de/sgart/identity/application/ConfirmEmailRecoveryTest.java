package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryProvisionedAccountRepository;
import de.sgart.identity.application.RecoveryEmailTestSupport.FakeFindAccountByEmail;
import de.sgart.identity.application.RecoveryEmailTestSupport.FakeGetAccountDetails;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailTestSupport.OrderedDeleteAccount;
import de.sgart.identity.application.RecoveryEmailTestSupport.OrderedRebindAccountCredential;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingDeleteAccount;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingRebindAccountCredential;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Clock;
import java.time.Instant;
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

    private final FakeFindAccountByEmail findAccountByEmail = new FakeFindAccountByEmail();
    private final InMemoryEmailRecoveryCodeStore emailRecoveryCodeStore = new InMemoryEmailRecoveryCodeStore();
    private final IdentityRecoveryCodeHasher hasher = new IdentityRecoveryCodeHasher();
    private final FakeGetAccountDetails getAccountDetails = new FakeGetAccountDetails();
    private final RecordingDeleteAccount deleteAccount = new RecordingDeleteAccount();
    private final InMemoryProvisionedAccountRepository provisionedAccountRepository =
            new InMemoryProvisionedAccountRepository();
    private final RecordingRebindAccountCredential rebindAccountCredential = new RecordingRebindAccountCredential();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ConfirmEmailRecovery confirmEmailRecovery = new ConfirmEmailRecovery(
            findAccountByEmail,
            emailRecoveryCodeStore,
            hasher,
            getAccountDetails,
            deleteAccount,
            provisionedAccountRepository,
            rebindAccountCredential,
            clock);

    @Test
    void confirmEmailRecovery_deletesThrowawayThenRebindsTargetUsernameAndPublicKey() {
        findAccountByEmail.registerAccount("person@example.com", TARGET);
        emailRecoveryCodeStore.store(TARGET, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.com", "042817");

        assertThat(deleteAccount.deletionOrder).containsExactly(THROWAWAY);
        assertThat(provisionedAccountRepository.contains(THROWAWAY)).isFalse();
        assertThat(rebindAccountCredential.rebinds).hasSize(1);
        var rebind = rebindAccountCredential.rebinds.get(0);
        assertThat(rebind.keycloakUserId()).isEqualTo(TARGET);
        assertThat(rebind.username()).isEqualTo("U2");
        assertThat(rebind.publicKey()).isEqualTo("K2");
        assertThat(emailRecoveryCodeStore.find(TARGET, RecoveryCodePurpose.RECOVER)).isEmpty();
    }

    @Test
    void confirmEmailRecovery_preservesTheTargetKeycloakUserId() {
        findAccountByEmail.registerAccount("person@example.com", TARGET);
        emailRecoveryCodeStore.store(TARGET, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.com", "042817");

        assertThat(rebindAccountCredential.rebinds.get(0).keycloakUserId()).isEqualTo(TARGET);
        assertThat(deleteAccount.deletedIds).doesNotContain(TARGET);
    }

    @Test
    void confirmEmailRecovery_withUnknownEmail_isRejectedTheSameWayAsAWrongCode() {
        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "nobody@example.com", "042817"))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmEmailRecovery_withWrongCode_isRejectedAndChangesNothing() {
        findAccountByEmail.registerAccount("person@example.com", TARGET);
        emailRecoveryCodeStore.store(TARGET, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.com", "000000"))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmEmailRecovery_withBlankCode_isRejectedFastWithoutHashingNull() {
        findAccountByEmail.registerAccount("person@example.com", TARGET);
        emailRecoveryCodeStore.store(TARGET, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.com", " "))
                .isInstanceOf(RecoveryCodeRejectedException.class);
        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.com", null))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmEmailRecovery_exhaustedAttempts_isRejected() {
        findAccountByEmail.registerAccount("person@example.com", TARGET);
        emailRecoveryCodeStore.store(TARGET, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        for (int i = 0; i < RecoveryCode.MAX_ATTEMPTS; i++) {
            emailRecoveryCodeStore.incrementAttempts(TARGET, RecoveryCodePurpose.RECOVER);
        }

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.com", "042817"))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmEmailRecovery_replayAfterSuccess_isRejectedBecauseTheCodeWasConsumed() {
        findAccountByEmail.registerAccount("person@example.com", TARGET);
        emailRecoveryCodeStore.store(TARGET, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));

        confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.com", "042817");

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.com", "042817"))
                .isInstanceOf(RecoveryCodeRejectedException.class);
        assertThat(rebindAccountCredential.rebinds).hasSize(1);
    }

    @Test
    void confirmEmailRecovery_targetIsTheCallersOwnThrowaway_isRejectedAndChangesNothing() {
        // The caller's own still-throwaway account resolving as the recovery target must never
        // reach delete-then-rebind: deleting it would delete the target too (Story 7.3 review
        // finding).
        findAccountByEmail.registerAccount("self@example.com", THROWAWAY);
        emailRecoveryCodeStore.store(THROWAWAY, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);

        assertThatThrownBy(() -> confirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "self@example.com", "042817"))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(deleteAccount.deletedIds).isEmpty();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmEmailRecovery_deletesTheThrowawayBeforeRebindingTheTarget() {
        findAccountByEmail.registerAccount("person@example.com", TARGET);
        emailRecoveryCodeStore.store(TARGET, RecoveryCodePurpose.RECOVER, hasher.hash("042817"), NOW.plusSeconds(60), NOW);
        getAccountDetails.register(THROWAWAY, "U2", "K2");
        provisionedAccountRepository.recordIfAbsent(THROWAWAY, NOW.minusSeconds(60));

        List<String> sharedLog = new ArrayList<>();
        ConfirmEmailRecovery orderedConfirmEmailRecovery = new ConfirmEmailRecovery(
                findAccountByEmail,
                emailRecoveryCodeStore,
                hasher,
                getAccountDetails,
                new OrderedDeleteAccount(sharedLog),
                provisionedAccountRepository,
                new OrderedRebindAccountCredential(sharedLog),
                clock);

        orderedConfirmEmailRecovery.confirmAndRebind(THROWAWAY_ID, "person@example.com", "042817");

        assertThat(sharedLog).containsExactly("delete:" + THROWAWAY_ID, "rebind:target-A1");
    }
}
