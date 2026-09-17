package de.sgart.identity.adapter.out;

import de.sgart.identity.application.FindAccountByEmail;
import de.sgart.identity.domain.KeycloakUserId;
import java.util.Optional;

/**
 * The Story 7.3 stand-in for {@link FindAccountByEmail}: never finds a match, since no real
 * Keycloak account exists when the Admin adapter is disabled (mirrors {@link
 * DeferredFindHouseholdMemberByEmail}). Wired whenever {@code
 * sgart.identity.keycloak-admin.enabled=false} (the default).
 */
public final class DeferredFindAccountByEmail implements FindAccountByEmail {

    @Override
    public Optional<KeycloakUserId> findByEmail(String email) {
        return Optional.empty();
    }
}
