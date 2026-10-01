package de.sgart.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain-owned port over the recovery-email binding index. It enforces two per-account rules:
 * at most one pending binding (saving a new pending binding replaces the earlier one) and at most
 * one confirmed binding (confirming replaces the earlier confirmed one).
 */
public interface RecoveryEmailBindingRepository {

    /**
     * Stores the pending binding, replacing any pending binding the same account holds. An account's
     * already confirmed binding of the very same address is kept as it is, never downgraded.
     */
    void savePending(RecoveryEmailBinding pendingBinding);

    Optional<RecoveryEmailBinding> findPendingFor(KeycloakUserId keycloakUserId);

    /**
     * Persists the confirmation of the account's pending binding and removes every other confirmed
     * binding of that account.
     */
    void confirm(RecoveryEmailBinding confirmedBinding);

    /** @return every account holding a <em>confirmed</em> binding for the digest; pending ones are ignored. */
    List<RecoveryEmailBinding> findConfirmedFor(RecoveryEmailDigest digest);

    Optional<RecoveryEmailBinding> findConfirmedFor(KeycloakUserId keycloakUserId);

    boolean hasConfirmedBindingFor(KeycloakUserId keycloakUserId);

    /** Deletes every binding (pending and confirmed) of the account: detach, sweep, and erasure. */
    void deleteAllFor(KeycloakUserId keycloakUserId);

    /** Data export: hint and confirmation time of every binding of the account, never the digest. */
    List<RecoveryEmailBindingExport> findAllFor(KeycloakUserId keycloakUserId);

    /** Retention: deletes pending bindings created before the instant; confirmed ones are kept. */
    void deletePendingCreatedBefore(Instant threshold);
}
