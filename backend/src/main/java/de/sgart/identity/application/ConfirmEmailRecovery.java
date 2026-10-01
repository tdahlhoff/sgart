package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailDigest;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The R1 rebind (Story 7.3, design §1.1, AC2): verifies the recovery code for the mailbox the
 * person typed, resolves which account to recover, then — <strong>in this exact order</strong> —
 * deletes the caller's own throwaway account (freeing its username, which Keycloak's uniqueness
 * constraint would otherwise reject the rebind on) and rebinds the target account's {@code
 * username}/{@code publicKey} to the throwaway device's own credential. The target's {@link
 * KeycloakUserId} is preserved across the recovery — only the throwaway device's own account is
 * deleted, never the target's.
 *
 * <p>A mailbox may be bound to several accounts. The caller's own throwaway is never a candidate.
 * With one candidate the rebind runs at once; with several, the first call verifies the code and
 * answers with the candidates (consuming nothing), and the second call names the chosen account and
 * is verified from scratch, so the server keeps no picker session.
 */
public final class ConfirmEmailRecovery {

    private static final Logger log = LoggerFactory.getLogger(ConfirmEmailRecovery.class);

    private final RecoveryEmailBindingRepository recoveryEmailBindingRepository;
    private final RecoveryEmailDigester recoveryEmailDigester;
    private final RecoveryRequestThrottle recoveryRequestThrottle;
    private final ResolveRecoveryCandidates resolveRecoveryCandidates;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final GetAccountDetails getAccountDetails;
    private final DeleteAccount deleteAccount;
    private final ProvisionedAccountRepository provisionedAccountRepository;
    private final RebindAccountCredential rebindAccountCredential;
    private final CreateAccount createAccount;
    private final Clock clock;
    private final VerifyRecoveryCode verifyRecoveryCode;

    public ConfirmEmailRecovery(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            RecoveryEmailDigester recoveryEmailDigester,
            RecoveryRequestThrottle recoveryRequestThrottle,
            ResolveRecoveryCandidates resolveRecoveryCandidates,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            GetAccountDetails getAccountDetails,
            DeleteAccount deleteAccount,
            ProvisionedAccountRepository provisionedAccountRepository,
            RebindAccountCredential rebindAccountCredential,
            CreateAccount createAccount,
            Clock clock) {
        this.recoveryEmailBindingRepository = Objects.requireNonNull(
                recoveryEmailBindingRepository, "recoveryEmailBindingRepository must not be null");
        this.recoveryEmailDigester =
                Objects.requireNonNull(recoveryEmailDigester, "recoveryEmailDigester must not be null");
        this.recoveryRequestThrottle =
                Objects.requireNonNull(recoveryRequestThrottle, "recoveryRequestThrottle must not be null");
        this.resolveRecoveryCandidates =
                Objects.requireNonNull(resolveRecoveryCandidates, "resolveRecoveryCandidates must not be null");
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
     * @param chosenAccountId the account the person picked among several candidates, or {@code
     *     null} on the first call.
     * @throws RecoveryCodeRejectedException if the mailbox has no candidate account (D-H, no
     *     enumeration — the same rejection as a wrong code), the code is wrong, expired, or
     *     attempt-exhausted, or {@code chosenAccountId} is not one of the candidates.
     * @throws CallerAccountNotFoundException if the caller's own account no longer exists (401 —
     *     the app's {@code AuthenticatedHttpClient} then re-signs-in silently and retries once).
     * @throws RecoveryRebindFailedException if the rebind failed after the throwaway was deleted;
     *     the throwaway is restored best-effort and the code row is kept for a retry.
     */
    public EmailRecoveryOutcome confirmAndRebind(
            String throwawayKeycloakUserId, String rawEmail, String code, String chosenAccountId) {
        Objects.requireNonNull(throwawayKeycloakUserId, "throwawayKeycloakUserId must not be null");
        KeycloakUserId throwawayCaller = new KeycloakUserId(throwawayKeycloakUserId);
        RecoveryEmailDigest digest = recoveryEmailDigester.digest(RecoveryEmailValidation.validated(rawEmail));
        RecoveryCodeSubject codeSubject = RecoveryCodeSubject.forAddress(digest);

        // The caller's own still-throwaway account must never be the recovery target: deleting it
        // below would delete the target too and brick the device (Story 7.3 review finding). It is
        // left out of the candidates, so such a mailbox is rejected exactly like a wrong code (D-H).
        List<KeycloakUserId> candidates = recoveryEmailBindingRepository.findConfirmedFor(digest).stream()
                .map(RecoveryEmailBinding::keycloakUserId)
                .filter(account -> !account.equals(throwawayCaller))
                .toList();
        if (candidates.isEmpty()) {
            throw new RecoveryCodeRejectedException("no confirmed account for this address");
        }

        verifyRecoveryCode.verify(codeSubject, RecoveryCodePurpose.RECOVER, code);

        if (chosenAccountId == null && candidates.size() > 1) {
            return new EmailRecoveryOutcome.ChooseAccount(resolveRecoveryCandidates.resolve(candidates));
        }
        KeycloakUserId target = chosenAccountId == null ? candidates.get(0) : chooseAmong(candidates, chosenAccountId);

        rebind(throwawayCaller, target);

        emailRecoveryCodeStore.delete(codeSubject, RecoveryCodePurpose.RECOVER);
        recoveryRequestThrottle.reset(digest);
        recoveryEmailBindingRepository.deleteAllFor(throwawayCaller);
        emailRecoveryCodeStore.deleteAll(throwawayCaller);
        return new EmailRecoveryOutcome.Rebound();
    }

    private static KeycloakUserId chooseAmong(List<KeycloakUserId> candidates, String chosenAccountId) {
        return candidates.stream()
                .filter(candidate -> candidate.value().equals(chosenAccountId))
                .findFirst()
                .orElseThrow(() -> new RecoveryCodeRejectedException("chosen account is not a candidate"));
    }

    private void rebind(KeycloakUserId throwawayCaller, KeycloakUserId target) {
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
