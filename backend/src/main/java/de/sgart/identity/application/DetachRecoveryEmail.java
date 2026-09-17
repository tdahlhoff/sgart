package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import java.util.Objects;

/**
 * Revokes the caller's attached recovery email (Story 7.3, AC4 — revocable consent / purpose
 * limitation, CLAUDE.md §5): clears the email and {@code emailVerified} on the Keycloak account
 * and deletes any of the account's pending code rows. Idempotent — detaching an already-unattached
 * email is a no-op, never an error.
 */
public final class DetachRecoveryEmail {

    private final SetAccountEmail setAccountEmail;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;

    public DetachRecoveryEmail(SetAccountEmail setAccountEmail, EmailRecoveryCodeStore emailRecoveryCodeStore) {
        this.setAccountEmail = Objects.requireNonNull(setAccountEmail, "setAccountEmail must not be null");
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

        setAccountEmail.clearEmail(caller);
        emailRecoveryCodeStore.deleteAll(caller);
    }
}
