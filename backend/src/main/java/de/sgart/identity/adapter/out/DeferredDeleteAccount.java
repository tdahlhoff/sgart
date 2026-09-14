package de.sgart.identity.adapter.out;

import de.sgart.identity.application.DeleteAccount;
import de.sgart.identity.domain.KeycloakUserId;

/**
 * The 7.1 stand-in for {@link DeleteAccount}: a no-op, since no real Keycloak account exists when
 * the Admin adapter is disabled (mirrors {@link DeferredCreateAccount}). Wired whenever {@code
 * sgart.identity.keycloak-admin.enabled=false} (the default).
 */
public final class DeferredDeleteAccount implements DeleteAccount {

    @Override
    public void delete(KeycloakUserId keycloakUserId) {
        // Intentionally empty — see class Javadoc.
    }
}
