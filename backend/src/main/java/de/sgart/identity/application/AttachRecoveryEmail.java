package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Clock;
import java.util.Objects;

/**
 * Attaches an email to the caller's own (real, authenticated) account (Story 7.3, AC1): sets the
 * email unverified, issues a 6-digit code, and sends it over SMTP. Only a subsequent {@link
 * ConfirmRecoveryEmail} makes the email count as an attached recovery email (AC1).
 */
public final class AttachRecoveryEmail {

    private final SetAccountEmail setAccountEmail;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final RecoveryCodeHasher recoveryCodeHasher;
    private final SendRecoveryCodeEmail sendRecoveryCodeEmail;
    private final Clock clock;

    public AttachRecoveryEmail(
            SetAccountEmail setAccountEmail,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            SendRecoveryCodeEmail sendRecoveryCodeEmail,
            Clock clock) {
        this.setAccountEmail = Objects.requireNonNull(setAccountEmail, "setAccountEmail must not be null");
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
        this.recoveryCodeHasher = Objects.requireNonNull(recoveryCodeHasher, "recoveryCodeHasher must not be null");
        this.sendRecoveryCodeEmail =
                Objects.requireNonNull(sendRecoveryCodeEmail, "sendRecoveryCodeEmail must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never taken from the request body.
     * @throws InvalidRecoveryEmailException if {@code rawEmail} is missing or not a plausible address.
     */
    public void attach(String keycloakUserId, String rawEmail) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        String email = RecoveryEmailValidation.validated(rawEmail);
        KeycloakUserId caller = new KeycloakUserId(keycloakUserId);

        setAccountEmail.setEmail(caller, email, false);

        String code = RecoveryCode.generate();
        emailRecoveryCodeStore.store(
                caller, RecoveryCodePurpose.ATTACH_CONFIRM, recoveryCodeHasher.hash(code), RecoveryCode.expiresAt(clock),
                clock.instant());
        sendRecoveryCodeEmail.send(email, code);
    }
}
