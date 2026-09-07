package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.Household;

/**
 * Raised by {@link Household#acceptInvite} when the invite has already been {@code ACCEPTED} and
 * the caller is <strong>not</strong> the member who consumed it (Story 4.2, AC5) — a spent personal
 * link cannot be ridden by a stranger. A plain domain exception carrying no client-facing {@code
 * code}/{@code ErrorDescriptor} (AD-1); mirrors {@link DuplicatePendingInviteException}.
 */
public final class InviteAlreadyConsumedException extends RuntimeException {

    public InviteAlreadyConsumedException(String message) {
        super(message);
    }
}
