package de.sgart.collaboration.application.exception;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised at the application/ACL seam when the invitee's email already resolves to a current
 * household member (Story 4.1, AC3, E5) — a {@code 409 Conflict}. Enforced at the seam, not in the
 * domain, because the {@code Household} aggregate carries no email at all (AD-6): members are
 * pseudonymous {@link de.sgart.shared.MemberId}s with no address to compare against. The real
 * Keycloak email lookup this depends on is deferred to Stories 4.2/4.6 (locked decision 2); 4.1 ships
 * the seam + its port + a fake-backed test.
 */
public final class AlreadyAHouseholdMemberApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public AlreadyAHouseholdMemberApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("invite.alreadyAMember", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
