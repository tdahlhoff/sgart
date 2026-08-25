package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.StoreName;

/**
 * Raised by {@link Household#addStore} / {@link Household#archiveStore} when {@code requestedBy} is
 * not a known member of the household (AC1: "Any Member" — membership, not a role, gates store
 * management). A defense-in-depth domain invariant: in the live flow the Identity ACL's {@code
 * ResolveMemberIdentity} already rejects a non-member with a {@code 403} before the aggregate is
 * ever loaded, so this guards against an ACL/event-stream divergence rather than a normal request.
 *
 * <p>A plain domain exception carrying no client-facing transport concern (AD-1), like {@link
 * StoreName}'s {@link IllegalArgumentException}.
 */
public final class NotAHouseholdMemberException extends RuntimeException {

    public NotAHouseholdMemberException(String message) {
        super(message);
    }
}
