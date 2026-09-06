package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.Household;

/**
 * Raised by {@link Household#invitePerson} when a <strong>non-expired</strong> pending invite to
 * the same email already exists in the household (Story 4.1, AC2). Deliberately a rejection, not a
 * convergent no-op (AD-8, §3.4) — re-inviting a still-pending email is a real error the caller
 * should see, not a silent success. Mirrors {@link DuplicateStoreNameException}: a plain domain
 * exception carrying no client-facing transport concern (AD-1).
 */
public final class DuplicatePendingInviteException extends RuntimeException {

    public DuplicatePendingInviteException(String message) {
        super(message);
    }
}
