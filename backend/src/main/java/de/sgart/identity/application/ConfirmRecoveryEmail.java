package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Clock;
import java.util.Objects;

/**
 * Confirms ownership of an email just attached via {@link AttachRecoveryEmail} (Story 7.3, AC1):
 * on a correct, unexpired, non-exhausted code, marks the Keycloak account's email verified and
 * consumes the code (single-use).
 */
public final class ConfirmRecoveryEmail {

    private final SetAccountEmail setAccountEmail;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final VerifyRecoveryCode verifyRecoveryCode;

    public ConfirmRecoveryEmail(
            SetAccountEmail setAccountEmail,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            Clock clock) {
        this.setAccountEmail = Objects.requireNonNull(setAccountEmail, "setAccountEmail must not be null");
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
        this.verifyRecoveryCode = new VerifyRecoveryCode(emailRecoveryCodeStore, recoveryCodeHasher, clock);
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never taken from the request body.
     * @throws RecoveryCodeRejectedException if the code is wrong, expired, or attempt-exhausted.
     */
    public void confirm(String keycloakUserId, String code) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        KeycloakUserId caller = new KeycloakUserId(keycloakUserId);

        verifyRecoveryCode.verify(caller, RecoveryCodePurpose.ATTACH_CONFIRM, code);

        setAccountEmail.markEmailVerified(caller);
        emailRecoveryCodeStore.delete(caller, RecoveryCodePurpose.ATTACH_CONFIRM);
    }
}
