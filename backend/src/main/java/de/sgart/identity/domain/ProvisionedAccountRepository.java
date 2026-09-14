package de.sgart.identity.domain;

import java.time.Instant;
import java.util.List;

/**
 * Domain-owned port over the account-lifecycle shell store (Story 7.1) — mirrors {@link
 * MemberMappingRepository}'s and {@link DeviceTokenRepository}'s shape (a durable JDBC adapter
 * plus an in-memory test double). Rows are addressed by {@link KeycloakUserId} alone, exactly as
 * the retention sweep and erasure (AD-7) need them.
 */
public interface ProvisionedAccountRepository {

    /**
     * Records a fresh shell, or does nothing if one already exists for this {@link KeycloakUserId}
     * (idempotent — a dropped-and-retried {@code POST /api/v1/accounts} never creates a second
     * row, AC3). The first-recorded {@code provisionedAt} always wins.
     */
    void recordIfAbsent(KeycloakUserId keycloakUserId, Instant provisionedAt);

    /** @return every shell whose {@code provisionedAt} is strictly before {@code threshold}. */
    List<ProvisionedAccount> findProvisionedBefore(Instant threshold);

    /**
     * Removes the shell row for {@code keycloakUserId} — a no-op when none exists (idempotent).
     * Called by the retention sweep (AC5) and, later, account erasure (AD-7).
     */
    void delete(KeycloakUserId keycloakUserId);
}
