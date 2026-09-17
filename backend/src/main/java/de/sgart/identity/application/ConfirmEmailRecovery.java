package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Clock;
import java.util.Objects;

/**
 * The R1 rebind (Story 7.3, design §1.1, AC2): verifies the recovery code for the account found
 * by email, then — <strong>in this exact order</strong> — deletes the caller's own throwaway
 * account (freeing its username, which Keycloak's uniqueness constraint would otherwise reject the
 * rebind on) and rebinds the target account's {@code username}/{@code publicKey} to the throwaway
 * device's own credential. The target's {@link KeycloakUserId} is preserved across the recovery —
 * only the throwaway device's own account is deleted, never the target's.
 */
public final class ConfirmEmailRecovery {

    private final FindAccountByEmail findAccountByEmail;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final GetAccountDetails getAccountDetails;
    private final DeleteAccount deleteAccount;
    private final ProvisionedAccountRepository provisionedAccountRepository;
    private final RebindAccountCredential rebindAccountCredential;
    private final VerifyRecoveryCode verifyRecoveryCode;

    public ConfirmEmailRecovery(
            FindAccountByEmail findAccountByEmail,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            GetAccountDetails getAccountDetails,
            DeleteAccount deleteAccount,
            ProvisionedAccountRepository provisionedAccountRepository,
            RebindAccountCredential rebindAccountCredential,
            Clock clock) {
        this.findAccountByEmail = Objects.requireNonNull(findAccountByEmail, "findAccountByEmail must not be null");
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
        this.getAccountDetails = Objects.requireNonNull(getAccountDetails, "getAccountDetails must not be null");
        this.deleteAccount = Objects.requireNonNull(deleteAccount, "deleteAccount must not be null");
        this.provisionedAccountRepository =
                Objects.requireNonNull(provisionedAccountRepository, "provisionedAccountRepository must not be null");
        this.rebindAccountCredential =
                Objects.requireNonNull(rebindAccountCredential, "rebindAccountCredential must not be null");
        this.verifyRecoveryCode = new VerifyRecoveryCode(emailRecoveryCodeStore, recoveryCodeHasher, clock);
    }

    /**
     * @param throwawayKeycloakUserId the {@code kc2} resolved from the caller's own (throwaway)
     *     JWT (D-C) — never taken from the request body.
     * @throws RecoveryCodeRejectedException if the email is unknown (D-H, no enumeration — the same
     *     rejection as a wrong code) or the code is wrong, expired, or attempt-exhausted.
     */
    public void confirmAndRebind(String throwawayKeycloakUserId, String rawEmail, String code) {
        Objects.requireNonNull(throwawayKeycloakUserId, "throwawayKeycloakUserId must not be null");
        KeycloakUserId throwawayCaller = new KeycloakUserId(throwawayKeycloakUserId);
        String email = RecoveryEmailValidation.validated(rawEmail);
        KeycloakUserId target = findAccountByEmail
                .findByEmail(email)
                .orElseThrow(() -> new RecoveryCodeRejectedException("no account for this email"));

        // A caller recovering into their own still-throwaway account (e.g. attached-but-not-yet-
        // rebound email pointing back at itself) must never reach the delete-then-rebind sequence
        // below: deleting `throwawayCaller` would delete `target` too, and the rebind would then
        // operate on an already-deleted id, bricking the device. Reject exactly like a wrong code
        // (Story 7.3 review finding) — no distinct signal, same no-enumeration posture (D-H).
        if (target.equals(throwawayCaller)) {
            throw new RecoveryCodeRejectedException("recovery target is the caller's own throwaway account");
        }

        verifyRecoveryCode.verify(target, RecoveryCodePurpose.RECOVER, code);

        AccountDetails throwawayDetails = getAccountDetails
                .findById(throwawayCaller)
                .orElseThrow(() -> new IllegalStateException(
                        "throwaway account " + throwawayCaller + " must exist (7.1 always provisions before recovery runs)"));

        // Delete-then-rebind order is load-bearing (design §1.1): Keycloak's username uniqueness
        // constraint means `target` cannot take `U2` while the throwaway still holds it. This
        // leaves a narrow, accepted infra-failure-only window: if `rebindAccountCredential.rebind`
        // below throws (Keycloak 5xx/network) after the throwaway is already gone, the device is
        // left authenticating into nothing, with no automatic compensation. Timo accepted this risk
        // 2026-09-16 rather than adding rollback/compensation complexity now; tracked as follow-up
        // hardening in deferred-work.md ("confirmAndRebind partial-failure compensation").
        deleteAccount.delete(throwawayCaller);
        provisionedAccountRepository.delete(throwawayCaller);

        rebindAccountCredential.rebind(target, throwawayDetails.username(), throwawayDetails.publicKey());
        emailRecoveryCodeStore.delete(target, RecoveryCodePurpose.RECOVER);
    }
}
