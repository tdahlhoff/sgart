package de.sgart.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Entity: the claim of one account on one recovery address, identified by the address digest and
 * the account. The same digest may be bound to several accounts. A binding is <em>pending</em>
 * until the code mailed to the address has been confirmed; a pending binding grants nothing
 * (no recovery, no sweep activation, no profile status).
 */
public record RecoveryEmailBinding(
        RecoveryEmailDigest digest,
        KeycloakUserId keycloakUserId,
        RecoveryEmailHint hint,
        Instant confirmedAt,
        Instant createdAt) {

    public RecoveryEmailBinding {
        Objects.requireNonNull(digest, "digest must not be null");
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        Objects.requireNonNull(hint, "hint must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static RecoveryEmailBinding pending(
            RecoveryEmailDigest digest, KeycloakUserId keycloakUserId, RecoveryEmailHint hint, Instant createdAt) {
        return new RecoveryEmailBinding(digest, keycloakUserId, hint, null, createdAt);
    }

    public RecoveryEmailBinding confirm(Instant confirmationTime) {
        Objects.requireNonNull(confirmationTime, "confirmationTime must not be null");
        return new RecoveryEmailBinding(digest, keycloakUserId, hint, confirmationTime, createdAt);
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }
}
