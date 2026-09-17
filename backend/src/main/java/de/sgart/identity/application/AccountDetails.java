package de.sgart.identity.application;

/**
 * The Admin-API "get user" projection {@link GetAccountDetails} returns (Story 7.3) — reused for
 * two purposes (DRY, one Keycloak endpoint): the retention sweep's confirmed-email check (design
 * §7, D-G) and reading the throwaway device's current {@code username}/{@code publicKey} before it
 * is deleted in the R1 rebind (design §1.1).
 */
public record AccountDetails(String username, String publicKey, String email, boolean emailVerified) {}
