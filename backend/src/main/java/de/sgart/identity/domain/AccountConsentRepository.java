package de.sgart.identity.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain-owned port over the consent-record store (Story 7.4, design §2) — mirrors {@link
 * ProvisionedAccountRepository}'s shape exactly (a durable JDBC adapter plus an in-memory test
 * double). Rows are addressed by {@link KeycloakUserId} alone.
 */
public interface AccountConsentRepository {

    /**
     * Upserts the caller's current consent — one row per {@link KeycloakUserId} (AC2). A re-accept
     * (same or a newer {@code noticeVersion}) overwrites the previously recorded row rather than
     * appending a second one.
     */
    void record(KeycloakUserId keycloakUserId, String noticeVersion, Instant acceptedAt);

    /** @return the caller's current consent row, or empty if none was ever recorded. */
    Optional<AccountConsent> findFor(KeycloakUserId keycloakUserId);

    /**
     * Removes the consent row for {@code keycloakUserId} — a no-op when none exists (idempotent).
     * The Epic 6 erasure hook (AC5, AD-7): 7.4 provides this method, Epic 6's erasure use case
     * calls it alongside the Keycloak-account delete. Also the sole mechanism for "revoke consent"
     * (design §7 — revocation routes to erasure, no separate withdrawn state).
     */
    void deleteFor(KeycloakUserId keycloakUserId);
}
