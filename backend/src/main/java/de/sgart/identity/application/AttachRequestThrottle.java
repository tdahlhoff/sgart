package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;

/**
 * Per-caller budget for attaching a recovery email: it protects the caller's own flow from
 * hammering. Keyed by the caller's pseudonymous account id, never by the address. Exceeding it is
 * visible to the caller ({@link RecoveryCodeRateLimitedException}), because it reveals nothing
 * about any address.
 */
public interface AttachRequestThrottle {

    /** @return {@code true} and records the attempt if within budget; {@code false}, without side effect, otherwise. */
    boolean tryAttach(KeycloakUserId caller);
}
