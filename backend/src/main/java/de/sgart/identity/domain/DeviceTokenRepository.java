package de.sgart.identity.domain;

import java.util.List;
import java.util.Optional;

/**
 * Domain-owned port over the device-token store (Story 4.5, AC5) — mirrors {@link
 * MemberMappingRepository}'s shape (a durable JDBC adapter plus an in-memory test double). Rows
 * are addressed by the opaque {@code token} itself (a device re-registers with the same token, an
 * upsert) and looked up by {@link KeycloakUserId} for both push fan-out (a person's every device)
 * and erasure (AD-7, Epic 6 hook: every device for a person).
 */
public interface DeviceTokenRepository {

    /** Inserts a new registration or refreshes an existing one for the same {@code token} (idempotent, AC5). */
    void upsert(DeviceToken deviceToken);

    /** @return the row for this token, or empty when never registered / already pruned. */
    Optional<DeviceToken> findByToken(String token);

    /** @return every device currently registered for the person, in no particular order. */
    List<DeviceToken> findByKeycloakUserId(KeycloakUserId keycloakUserId);

    /**
     * Removes one device's registration — a no-op when the token is unknown (idempotent). The
     * sign-out unregister path and the transport's stale-token prune path (AC5) share this method.
     */
    void deleteByToken(String token);

    /**
     * Removes every device registered for the person — the account-erasure hook (AD-7). Wired by
     * Epic 6's account-deletion use case, not by this story (Story 4.5 Task 1 note); a no-op when
     * none exist (idempotent).
     */
    void deleteAllForUser(KeycloakUserId keycloakUserId);
}
