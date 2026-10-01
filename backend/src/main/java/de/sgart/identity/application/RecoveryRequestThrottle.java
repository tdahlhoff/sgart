package de.sgart.identity.application;

import de.sgart.identity.domain.RecoveryEmailDigest;

/**
 * Per-address budget for recovery code requests. It is separate from the attach budgets, so
 * nobody can lock an owner out of attaching or recovering by burning the budget of the other
 * flow. A successful recovery resets it.
 */
public interface RecoveryRequestThrottle {

    /** @return {@code true} and records the request if within budget; {@code false}, without side effect, otherwise. */
    boolean tryRequest(RecoveryEmailDigest digest);

    void reset(RecoveryEmailDigest digest);
}
