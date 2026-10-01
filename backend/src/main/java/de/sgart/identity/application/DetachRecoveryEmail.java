package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import java.util.Objects;

/**
 * Revokes the caller's recovery email (revocable consent / purpose limitation, CLAUDE.md §5):
 * deletes every binding of the account, pending and confirmed, and the account's code rows.
 * Idempotent — detaching when nothing is attached is a no-op, never an error. It deliberately
 * resets no throttle, so detach-and-reattach cannot be used to dodge a budget.
 */
public final class DetachRecoveryEmail {

    private final RecoveryEmailBindingRepository recoveryEmailBindingRepository;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;

    public DetachRecoveryEmail(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            EmailRecoveryCodeStore emailRecoveryCodeStore) {
        this.recoveryEmailBindingRepository = Objects.requireNonNull(
                recoveryEmailBindingRepository, "recoveryEmailBindingRepository must not be null");
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never taken from the request body.
     */
    public void detach(String keycloakUserId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        KeycloakUserId caller = new KeycloakUserId(keycloakUserId);

        recoveryEmailBindingRepository.deleteAllFor(caller);
        emailRecoveryCodeStore.deleteAll(caller);
    }
}
