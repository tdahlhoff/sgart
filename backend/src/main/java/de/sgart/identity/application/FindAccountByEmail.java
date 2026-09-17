package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;
import java.util.Optional;

/**
 * Admin-API exact-match email lookup (Story 7.3, design §3.2) — resolves the account recovery is
 * requested for. Never reveals to the caller whether a match existed (D-H, no enumeration); that
 * discipline lives in the application services that consume this port, not here.
 */
public interface FindAccountByEmail {

    Optional<KeycloakUserId> findByEmail(String email);
}
