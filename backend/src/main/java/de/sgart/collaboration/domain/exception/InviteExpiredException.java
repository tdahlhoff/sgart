package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.Household;

/**
 * Raised by {@link Household#acceptInvite} when the invite cannot be redeemed because it is past
 * its TTL — whether the expiry is discovered lazily on this very call (Story 4.2, AC3) or the
 * invite was already {@code EXPIRED} on a prior call. A plain domain exception carrying no
 * client-facing {@code code}/{@code ErrorDescriptor} (AD-1); mirrors {@link
 * DuplicatePendingInviteException}.
 */
public final class InviteExpiredException extends RuntimeException {

    public InviteExpiredException(String message) {
        super(message);
    }
}
