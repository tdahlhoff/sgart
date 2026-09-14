package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;

/**
 * The Identity ACL's account-deletion port (Story 7.1) — the counterpart to {@link CreateAccount}
 * used by the retention sweep (AC5) to remove a never-activated shell's Keycloak account. The real
 * Keycloak Admin call lives in {@code adapter.out.KeycloakAdminCreateAccount} (AD-1/AD-2); the 7.1
 * stand-in, {@code DeferredDeleteAccount}, stays wired whenever {@code
 * sgart.identity.keycloak-admin.enabled=false}.
 */
public interface DeleteAccount {

    /**
     * Deletes the Keycloak account for {@code keycloakUserId} — idempotent: an account already
     * gone (e.g. a re-run sweep) is a no-op, never an error.
     */
    void delete(KeycloakUserId keycloakUserId);
}
