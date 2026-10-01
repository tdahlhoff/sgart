package de.sgart.identity.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised by {@link IssueMemberIdentity#persist} when the caller is already mapped to a different
 * {@code MemberId} for the household — they lost a concurrent first-time mapping write (a join
 * from two devices at once, or a repeated create). The write is rejected before anything is
 * appended; a manual retry then finds the winner's mapping.
 */
public final class MemberMappingConflictException extends RuntimeException {

    private static final String ERROR_CODE = "membership.mappingConflict";

    private final ErrorDescriptor errorDescriptor;

    public MemberMappingConflictException() {
        this(null);
    }

    public MemberMappingConflictException(Throwable cause) {
        super("Caller is already mapped to a different member id for this household", cause);
        this.errorDescriptor = ErrorDescriptor.of(ERROR_CODE, getMessage());
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
