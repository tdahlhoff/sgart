package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;

/**
 * Admin-API capability to mutate the email attached to a Keycloak account (Story 7.3, design §2).
 * The email lives <strong>only</strong> on the Keycloak account (AD-6) — SGART persists none of
 * it. Implemented by the same adapter as {@link CreateAccount}/{@link DeleteAccount} ({@code
 * KeycloakAdminCreateAccount}, design §10 "extend the existing Keycloak Admin adapter").
 */
public interface SetAccountEmail {

    /** Attach: sets the email with {@code emailVerified := verified} (Story 7.3 attach: {@code false}). */
    void setEmail(KeycloakUserId keycloakUserId, String email, boolean verified);

    /** Confirm: flips {@code emailVerified := true} without changing the email itself. */
    void markEmailVerified(KeycloakUserId keycloakUserId);

    /** Detach: clears the email and {@code emailVerified} on the account. */
    void clearEmail(KeycloakUserId keycloakUserId);
}
