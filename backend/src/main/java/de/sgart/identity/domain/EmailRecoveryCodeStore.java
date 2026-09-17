package de.sgart.identity.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain-owned port over the one-time-code store (Story 7.3, design §4) — mirrors {@link
 * ProvisionedAccountRepository}'s shape: a durable JDBC adapter plus an in-memory test double.
 * Rows are addressed by {@code (keycloakUserId, purpose)} alone.
 */
public interface EmailRecoveryCodeStore {

    /**
     * Stores a fresh hashed code for {@code (keycloakUserId, purpose)}, replacing any code already
     * active for that pair (one active code per purpose per subject, design §4).
     */
    void store(
            KeycloakUserId keycloakUserId,
            RecoveryCodePurpose purpose,
            String codeHash,
            Instant expiresAt,
            Instant createdAt);

    /** @return the active code row for {@code (keycloakUserId, purpose)}, if any. */
    Optional<EmailRecoveryCode> find(KeycloakUserId keycloakUserId, RecoveryCodePurpose purpose);

    /** Increments the wrong-guess counter for {@code (keycloakUserId, purpose)} — a no-op if none exists. */
    void incrementAttempts(KeycloakUserId keycloakUserId, RecoveryCodePurpose purpose);

    /** Deletes the row for {@code (keycloakUserId, purpose)} — a no-op if none exists (single-use on success). */
    void delete(KeycloakUserId keycloakUserId, RecoveryCodePurpose purpose);

    /** Deletes every row (both purposes) for {@code keycloakUserId} — detach, sweep, and erasure cleanup. */
    void deleteAll(KeycloakUserId keycloakUserId);
}
