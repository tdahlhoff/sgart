package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Clock;
import java.util.Objects;

/**
 * Attaches a recovery email to the caller's own (real, authenticated) account: writes a
 * <em>pending</em> binding keyed by the address digest and mails a 6-digit code. Only a subsequent
 * {@link ConfirmRecoveryEmail} makes the binding count. The address is stored nowhere, and nothing
 * about it (already held by another account, over its mail budget) changes the status or body the
 * caller sees: the only visible refusals are a malformed address and the caller's own budget. The
 * over-mail-budget branch returns before the writes, so response time can reveal that this address
 * already received its attach mails today (accepted: a weak signal).
 */
public final class AttachRecoveryEmail {

    private final RecoveryEmailBindingRepository recoveryEmailBindingRepository;
    private final RecoveryEmailDigester recoveryEmailDigester;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final RecoveryCodeHasher recoveryCodeHasher;
    private final SendRecoveryCodeEmail sendRecoveryCodeEmail;
    private final AttachRequestThrottle attachRequestThrottle;
    private final AttachMailThrottle attachMailThrottle;
    private final Clock clock;

    public AttachRecoveryEmail(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            RecoveryEmailDigester recoveryEmailDigester,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            SendRecoveryCodeEmail sendRecoveryCodeEmail,
            AttachRequestThrottle attachRequestThrottle,
            AttachMailThrottle attachMailThrottle,
            Clock clock) {
        this.recoveryEmailBindingRepository = Objects.requireNonNull(
                recoveryEmailBindingRepository, "recoveryEmailBindingRepository must not be null");
        this.recoveryEmailDigester =
                Objects.requireNonNull(recoveryEmailDigester, "recoveryEmailDigester must not be null");
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
        this.recoveryCodeHasher = Objects.requireNonNull(recoveryCodeHasher, "recoveryCodeHasher must not be null");
        this.sendRecoveryCodeEmail =
                Objects.requireNonNull(sendRecoveryCodeEmail, "sendRecoveryCodeEmail must not be null");
        this.attachRequestThrottle =
                Objects.requireNonNull(attachRequestThrottle, "attachRequestThrottle must not be null");
        this.attachMailThrottle = Objects.requireNonNull(attachMailThrottle, "attachMailThrottle must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never taken from the request body.
     * @throws InvalidRecoveryEmailException if {@code rawEmail} is missing or not a plausible address.
     * @throws RecoveryCodeRateLimitedException if the caller is over their own attach budget;
     *     checked before any side effect.
     */
    public void attach(String keycloakUserId, String rawEmail) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        String address = RecoveryEmailValidation.validated(rawEmail);
        KeycloakUserId caller = new KeycloakUserId(keycloakUserId);

        if (!attachRequestThrottle.tryAttach(caller)) {
            throw new RecoveryCodeRateLimitedException("recovery email attach rate-limited for this account");
        }

        RecoveryEmailDigest digest = recoveryEmailDigester.digest(address);
        // Silent on purpose: the response must stay identical whether or not this address has
        // used up its mail budget, so the budget can never serve as a signal about the address.
        if (!attachMailThrottle.tryMail(digest)) {
            return;
        }

        recoveryEmailBindingRepository.savePending(RecoveryEmailBinding.pending(
                digest, caller, RecoveryEmailHint.masking(address), clock.instant()));

        String code = RecoveryCode.generate();
        emailRecoveryCodeStore.store(
                RecoveryCodeSubject.forAccount(caller),
                RecoveryCodePurpose.ATTACH_CONFIRM,
                recoveryCodeHasher.hash(code),
                RecoveryCode.expiresAt(clock),
                clock.instant());
        sendRecoveryCodeEmail.sendAttachConfirmationCode(address, code);
    }
}
