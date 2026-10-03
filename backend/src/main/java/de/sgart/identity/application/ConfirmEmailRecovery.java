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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The R1 rebind (Story 7.3, design §1.1, AC2): verifies the recovery code for the mailbox the
 * person typed, resolves which account to recover, then — <strong>in this exact order</strong> —
 * parks the caller's own throwaway account under a different username (freeing its username, which
 * Keycloak's uniqueness constraint would otherwise reject the rebind on), rebinds the target
 * account's {@code username}/{@code publicKey} to the throwaway device's own credential, and only
 * then deletes the throwaway. The target's {@link KeycloakUserId} is preserved across the recovery,
 * and a failed rebind gives the throwaway its username back under the <em>same</em> id — a
 * recreated account would get a new id and orphan everything keyed by the old one (a household the
 * throwaway created, its consent, its nickname).
 *
 * <p>A mailbox may be bound to several accounts. The caller's own throwaway is never a candidate.
 * With one candidate the rebind runs at once; with several, the first call verifies the code and
 * answers with the candidates (consuming nothing), and the second call names the chosen account and
 * is verified from scratch, so the server keeps no picker session.
 */
public final class ConfirmEmailRecovery {

    private static final Logger log = LoggerFactory.getLogger(ConfirmEmailRecovery.class);

    /** Never a derived device username (those are bare base64url), so a parked name cannot collide. */
    private static final String PARKED_USERNAME_PREFIX = "recovering-";

    private final RecoveryEmailBindingRepository recoveryEmailBindingRepository;
    private final RecoveryEmailDigester recoveryEmailDigester;
    private final RecoveryRequestThrottle recoveryRequestThrottle;
    private final ResolveRecoveryCandidates resolveRecoveryCandidates;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final GetAccountDetails getAccountDetails;
    private final DeleteAccount deleteAccount;
    private final ProvisionedAccountRepository provisionedAccountRepository;
    private final RebindAccountCredential rebindAccountCredential;
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
     * @throws RecoveryRebindFailedException if the rebind failed; the throwaway got its username
     *     back and the code row is kept for a retry.
     * @throws RecoveryThrowawayRestoreFailedException if the rebind failed and the throwaway could
     *     not be given its username back either; the code row is kept for a retry.
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
            // Audit trail for what the picker reveals (household names and nicknames): counts and the
            // pseudonymous caller id only, never the address or any name.
            log.info(
                    "Recovery picker shown: {} candidate accounts for throwaway account {}",
                    candidates.size(),
                    throwawayCaller.value());
            return new EmailRecoveryOutcome.ChooseAccount(resolveRecoveryCandidates.resolve(candidates));
        }
        KeycloakUserId target = chosenAccountId == null ? candidates.get(0) : chooseAmong(candidates, chosenAccountId);

        rebind(throwawayCaller, target);

        cleanUpAfterRebind("delete the throwaway account", () -> deleteThrowaway(throwawayCaller));
        cleanUpAfterRebind(
                "delete the recovery code", () -> emailRecoveryCodeStore.delete(codeSubject, RecoveryCodePurpose.RECOVER));
        cleanUpAfterRebind("reset the recovery budget", () -> recoveryRequestThrottle.reset(digest));
        cleanUpAfterRebind(
                "delete the throwaway's bindings", () -> recoveryEmailBindingRepository.deleteAllFor(throwawayCaller));
        cleanUpAfterRebind("delete the throwaway's codes", () -> emailRecoveryCodeStore.deleteAll(throwawayCaller));
        return new EmailRecoveryOutcome.Rebound();
    }

    /**
     * The rebind has succeeded and cannot be undone, so a failing cleanup step must not turn the
     * recovery into an error: it is logged (class only, never the address) and the rest still runs.
     */
    private static void cleanUpAfterRebind(String description, Runnable cleanupStep) {
        try {
            cleanupStep.run();
        } catch (RuntimeException cleanupFailure) {
            log.error(
                    "Recovery succeeded but failed to {}: {}", description, cleanupFailure.getClass().getName());
        }
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

        // Park-rebind-delete order is load-bearing (design §1.1): Keycloak's username uniqueness
        // constraint means `target` cannot take `U2` while the throwaway still holds it, so the
        // throwaway is renamed out of the way first. Deleting it only after the rebind succeeded is
        // what keeps its id — and everything keyed by it — alive when the rebind fails.
        String originalUsername = throwawayDetails.username();
        try {
            rebindAccountCredential.rebind(
                    throwawayCaller, PARKED_USERNAME_PREFIX + originalUsername, throwawayDetails.publicKey());
            rebindAccountCredential.rebind(target, originalUsername, throwawayDetails.publicKey());
        } catch (RuntimeException rebindFailure) {
            if (holdsUsername(target, originalUsername)) {
                // The rebind was applied and only its response was lost: a successful recovery.
                log.warn("Recovery rebind was applied although its call failed; completing the recovery", rebindFailure);
                return;
            }
            giveThrowawayItsUsernameBack(throwawayCaller, throwawayDetails, rebindFailure);
            log.error("Recovery rebind failed; the throwaway account kept its id and username", rebindFailure);
            throw new RecoveryRebindFailedException(rebindFailure);
        }
    }

    private boolean holdsUsername(KeycloakUserId account, String username) {
        try {
            return getAccountDetails
                    .findById(account)
                    .map(details -> details.username().equals(username))
                    .orElse(false);
        } catch (RuntimeException lookupFailure) {
            return false;
        }
    }

    /**
     * Gives the throwaway its username back under its own, unchanged id. When even that fails the
     * device cannot sign in until a retry succeeds, which is surfaced as its own failure rather than
     * hidden — the code row is kept, so an immediate retry heals it.
     */
    private void giveThrowawayItsUsernameBack(
            KeycloakUserId throwawayCaller, AccountDetails throwawayDetails, RuntimeException rebindFailure) {
        try {
            rebindAccountCredential.rebind(throwawayCaller, throwawayDetails.username(), throwawayDetails.publicKey());
        } catch (RuntimeException unparkFailure) {
            if (holdsUsername(throwawayCaller, throwawayDetails.username())) {
                return; // Parking never took effect, so there was nothing to give back.
            }
            rebindFailure.addSuppressed(unparkFailure);
            log.error("Recovery rebind failed and the throwaway account could not get its username back", rebindFailure);
            throw new RecoveryThrowawayRestoreFailedException(rebindFailure);
        }
    }

    private void deleteThrowaway(KeycloakUserId throwawayCaller) {
        deleteAccount.delete(throwawayCaller);
        provisionedAccountRepository.delete(throwawayCaller);
    }
}
