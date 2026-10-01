package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;
import java.util.Optional;

/**
 * Admin-API get-user capability (Story 7.3): the throwaway device's current {@code
 * username}/{@code publicKey} the R1 rebind reads before deleting it (design §1.1).
 */
public interface GetAccountDetails {

    Optional<AccountDetails> findById(KeycloakUserId keycloakUserId);
}
