package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The R1 rebind (Story 7.3, design §1.1, AC2): verifies the recovery code for the account found
 * by email, then — <strong>in this exact order</strong> — deletes the caller's own throwaway
 * account (freeing its username, which Keycloak's uniqueness constraint would otherwise reject the
 * rebind on) and rebinds the target account's {@code username}/{@code publicKey} to the throwaway
 * device's own credential. The target's {@link KeycloakUserId} is preserved across the recovery —
 * only the throwaway device's own account is deleted, never the target's.
 */
public final class ConfirmEmailRecovery {

    private static final Logger log = LoggerFactory.getLogger(ConfirmEmailRecovery.class);

    private final FindAccountByEmail findAccountByEmail;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final GetAccountDetails getAccountDetails;
    private final DeleteAccount deleteAccount;
    private final ProvisionedAccountRepository provisionedAccountRepository;
    private final RebindAccountCredential rebindAccountCredential;
    private final CreateAccount createAccount;
    private final Clock clock;
    private final VerifyRecoveryCode verifyRecoveryCode;

    public ConfirmEmailRecovery(
            FindAccountByEmail findAccountByEmail,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            GetAccountDetails getAccountDetails,
            DeleteAccount deleteAccount,
            ProvisionedAccountRepository provisionedAccountRepository,
            RebindAccountCredential rebindAccountCredential,
            CreateAccount createAccount,
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
        this.createAccount = Objects.requireNonNull(createAccount, "createAccount must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.verifyRecoveryCode = new VerifyRecoveryCode(emailRecoveryCodeStore, recoveryCodeHasher, clock);
    }

    /**
     * @param throwawayKeycloakUserId the {@code kc2} resolved from the caller's own (throwaway)
     *     JWT (D-C) — never taken from the request body.
     * @throws RecoveryCodeRejectedException if the email is unknown (D-H, no enumeration — the same
     *     rejection as a wrong code) or the code is wrong, expired, or attempt-exhausted.
     * @throws CallerAccountNotFoundException if the caller's own account no longer exists (401 —
     *     the app's {@code AuthenticatedHttpClient} then re-signs-in silently and retries once).
     * @throws RecoveryRebindFailedException if the rebind failed after the throwaway was deleted;
     *     the throwaway is restored best-effort and the code row is kept for a retry.
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
                .orElseThrow(() -> new CallerAccountNotFoundException(
                        "the caller's account no longer exists"));

        // Delete-then-rebind order is load-bearing (design §1.1): Keycloak's username uniqueness
        // constraint means `target` cannot take `U2` while the throwaway still holds it. If the
        // rebind then fails (Keycloak 5xx/network), the throwaway is restored from the details read
        // above so the device can authenticate again, and the RECOVER code row is kept (it is only
        // deleted after a successful rebind) so the person can retry without a new code.
        deleteAccount.delete(throwawayCaller);

        try {
            provisionedAccountRepository.delete(throwawayCaller);
            rebindAccountCredential.rebind(target, throwawayDetails.username(), throwawayDetails.publicKey());
        } catch (RuntimeException rebindFailure) {
            Optional<KeycloakUserId> restored = restoreThrowaway(target, throwawayDetails, rebindFailure);
            if (restored.isPresent() && restored.get().equals(target)) {
                // The username already belongs to the target: the rebind was applied and only its
                // response was lost. That is a successful recovery, not a failure to retry.
                log.warn("Recovery rebind was applied although its call failed; completing the recovery", rebindFailure);
            } else {
                log.error(
                        "Recovery rebind failed after the throwaway account was deleted; throwaway restored: {}",
                        restored.isPresent(),
                        rebindFailure);
                throw new RecoveryRebindFailedException(rebindFailure);
            }
        }
        emailRecoveryCodeStore.delete(target, RecoveryCodePurpose.RECOVER);
    }

    /**
     * Best effort: a failure here is attached to the rebind failure rather than masking it, and the
     * device then stays unprovisioned until its next launch re-provisions it (7.1). Returns the
     * id of the account now holding the throwaway's username — the {@code target} itself when the
     * rebind had in fact been applied — or empty when the restore failed. Only a genuinely
     * restored throwaway is recorded as a provisioned shell.
     */
    private Optional<KeycloakUserId> restoreThrowaway(
            KeycloakUserId target, AccountDetails throwawayDetails, RuntimeException rebindFailure) {
        try {
            KeycloakUserId holder = createAccount.create(throwawayDetails.username(), throwawayDetails.publicKey());
            if (!holder.equals(target)) {
                provisionedAccountRepository.recordIfAbsent(holder, clock.instant());
            }
            return Optional.of(holder);
        } catch (RuntimeException restoreFailure) {
            rebindFailure.addSuppressed(restoreFailure);
            return Optional.empty();
        }
    }
}
