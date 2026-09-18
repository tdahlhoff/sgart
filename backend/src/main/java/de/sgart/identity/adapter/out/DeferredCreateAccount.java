package de.sgart.identity.adapter.out;

import de.sgart.identity.application.CreateAccount;
import de.sgart.identity.domain.KeycloakUserId;

/**
 * The 7.1 stand-in for {@link CreateAccount}: never talks to Keycloak. Wired whenever {@code
 * sgart.identity.keycloak-admin.enabled=false} (the default — tests, CI, local dev with no admin
 * credentials configured).
 *
 * <p>Deterministic on {@code username} (already a stable, locally-derived encoding of the public
 * key, D-E) rather than a random id, so {@code ProvisionAccount}'s idempotency is exercisable
 * end-to-end without a real Keycloak Admin adapter: a retry with the same username reuses the same
 * pseudonymous id here exactly as the real adapter reuses the same Keycloak account.
 */
public final class DeferredCreateAccount implements CreateAccount {

    @Override
    public KeycloakUserId create(String username, String publicKey) {
        return new KeycloakUserId(username);
    }
}
