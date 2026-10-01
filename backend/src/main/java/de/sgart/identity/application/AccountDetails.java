package de.sgart.identity.application;

/**
 * The Admin-API "get user" projection {@link GetAccountDetails} returns (Story 7.3): the throwaway
 * device's current {@code username}/{@code publicKey}, read before it is deleted in the R1 rebind
 * (design §1.1).
 */
public record AccountDetails(String username, String publicKey) {}
