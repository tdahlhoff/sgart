package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;

/**
 * The Identity ACL's account-creation port (Story 7.1) — creates the Keycloak account a device's
 * public key is bound to, or idempotently reuses the existing one. Published as a plain {@code
 * (String, String) -> KeycloakUserId} contract so the derived-username/public-key shapes stay
 * transport-agnostic; the real Keycloak Admin call lives entirely in {@code
 * adapter.out.KeycloakAdminCreateAccount} (AD-1/AD-2), mirroring {@link FindHouseholdMemberByEmail}.
 *
 * <p>The 7.1 stand-in, {@code DeferredCreateAccount}, stays wired whenever {@code
 * sgart.identity.keycloak-admin.enabled=false} (the default — tests, CI, local dev with no admin
 * credentials configured).
 */
public interface CreateAccount {

    /**
     * @param username the deterministic, locally-derivable base64url encoding of the device's
     *     Ed25519 public key (D-E) — never a server-issued identifier.
     * @param publicKey the same base64url-encoded public key, registered as a user attribute (D-D)
     *     so the Direct-Grant authenticator SPI can verify signed login challenges against it.
     * @return the Keycloak-assigned internal user id (the pseudonym stored in {@link
     *     de.sgart.identity.domain.ProvisionedAccount} and, later, {@link
     *     de.sgart.identity.domain.MemberMapping}) — the same id whether this call created a fresh
     *     account or found one that already existed for this username (idempotent, AC3).
     */
    KeycloakUserId create(String username, String publicKey);
}
