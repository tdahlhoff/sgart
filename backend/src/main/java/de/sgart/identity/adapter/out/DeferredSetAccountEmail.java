package de.sgart.identity.adapter.out;

import de.sgart.identity.application.SetAccountEmail;
import de.sgart.identity.domain.KeycloakUserId;

/**
 * The Story 7.3 stand-in for {@link SetAccountEmail}: no real Keycloak account exists when the
 * Admin adapter is disabled, so every mutation is a no-op (mirrors {@link DeferredDeleteAccount}).
 * Wired whenever {@code sgart.identity.keycloak-admin.enabled=false} (the default).
 */
public final class DeferredSetAccountEmail implements SetAccountEmail {

    @Override
    public void setEmail(KeycloakUserId keycloakUserId, String email, boolean verified) {
        // Intentionally empty — see class Javadoc.
    }

    @Override
    public void markEmailVerified(KeycloakUserId keycloakUserId) {
        // Intentionally empty — see class Javadoc.
    }

    @Override
    public void clearEmail(KeycloakUserId keycloakUserId) {
        // Intentionally empty — see class Javadoc.
    }
}
