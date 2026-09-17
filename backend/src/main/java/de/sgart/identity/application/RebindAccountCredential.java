package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;

/**
 * The R1 rebind's Admin-API mutation (Story 7.3, design §1.1): sets the recovered account's
 * {@code username} and {@code publicKey} attribute to the new device's, preserving the account's
 * stable {@link KeycloakUserId}. The {@code keycloak-authenticator} SPI reads one {@code
 * publicKey} attribute and resolves by username — this port is the entire key-replacement
 * mechanism; the SPI itself never changes.
 */
public interface RebindAccountCredential {

    void rebind(KeycloakUserId keycloakUserId, String username, String publicKey);
}
