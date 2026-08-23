package de.sgart.identity.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised by {@link ResolveMemberIdentity} when a {@code (keycloakUserId, householdId)} pair has
 * no mapping — the caller is not a member of that household. This is always an authorization
 * failure, never a signal to mint a new {@code MemberId} (AD-5).
 */
public final class NotAMemberException extends RuntimeException {

    private static final String ERROR_CODE = "identity.notAMember";

    private final ErrorDescriptor errorDescriptor;

    public NotAMemberException() {
        super("Caller is not a member of the requested household");
        this.errorDescriptor = ErrorDescriptor.of(ERROR_CODE, getMessage());
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
