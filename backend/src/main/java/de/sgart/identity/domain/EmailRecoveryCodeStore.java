package de.sgart.identity.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain-owned port over the one-time-code store (Story 7.3, design §4) — mirrors {@link
 * ProvisionedAccountRepository}'s shape: a durable JDBC adapter plus an in-memory test double.
 * Rows are addressed by {@code (subject, purpose)} alone.
 */
public interface EmailRecoveryCodeStore {

    /**
     * Stores a fresh hashed code for {@code (subject, purpose)}, replacing any code already
     * active for that pair (one active code per purpose per subject, design §4).
     */
    void store(
            RecoveryCodeSubject subject,
            RecoveryCodePurpose purpose,
            String codeHash,
            Instant expiresAt,
            Instant createdAt);

    /** @return the active code row for {@code (subject, purpose)}, if any. */
    Optional<EmailRecoveryCode> find(RecoveryCodeSubject subject, RecoveryCodePurpose purpose);

    /** Increments the wrong-guess counter for {@code (subject, purpose)} — a no-op if none exists. */
    void incrementAttempts(RecoveryCodeSubject subject, RecoveryCodePurpose purpose);

    /** Deletes the row for {@code (subject, purpose)} — a no-op if none exists (single-use on success). */
    void delete(RecoveryCodeSubject subject, RecoveryCodePurpose purpose);

    /** Deletes every account-keyed row (both purposes) of {@code keycloakUserId} — detach, sweep, and erasure cleanup. */
    void deleteAll(KeycloakUserId keycloakUserId);

    /** Retention: deletes every row whose code expired before {@code threshold}. */
    void deleteExpiredBefore(Instant threshold);
}
