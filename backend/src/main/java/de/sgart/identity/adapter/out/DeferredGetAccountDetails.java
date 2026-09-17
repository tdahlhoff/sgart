package de.sgart.identity.adapter.out;

import de.sgart.identity.application.AccountDetails;
import de.sgart.identity.application.GetAccountDetails;
import de.sgart.identity.domain.KeycloakUserId;
import java.util.Optional;

/**
 * The Story 7.3 stand-in for {@link GetAccountDetails}: never finds a row, since no real Keycloak
 * account exists when the Admin adapter is disabled (mirrors {@link DeferredFindAccountByEmail}).
 * Wired whenever {@code sgart.identity.keycloak-admin.enabled=false} (the default).
 */
public final class DeferredGetAccountDetails implements GetAccountDetails {

    @Override
    public Optional<AccountDetails> findById(KeycloakUserId keycloakUserId) {
        return Optional.empty();
    }
}
