package de.sgart.identity.adapter.out;

import de.sgart.identity.application.RebindAccountCredential;
import de.sgart.identity.domain.KeycloakUserId;

/**
 * The Story 7.3 stand-in for {@link RebindAccountCredential}: a no-op, since no real Keycloak
 * account exists when the Admin adapter is disabled (mirrors {@link DeferredDeleteAccount}).
 * Wired whenever {@code sgart.identity.keycloak-admin.enabled=false} (the default).
 */
public final class DeferredRebindAccountCredential implements RebindAccountCredential {

    @Override
    public void rebind(KeycloakUserId keycloakUserId, String username, String publicKey) {
        // Intentionally empty — see class Javadoc.
    }
}
