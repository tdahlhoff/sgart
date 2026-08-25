package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.NotAHouseholdMemberException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@code NotAHouseholdMemberException} into the
 * stable, client-localizable {@code identity.notAMember} code — the same code the Identity ACL's
 * {@code NotAMemberException} already yields, so the two membership rejections look identical to the
 * client (a clean {@code 403}). Lives here, not in {@code collaboration.domain}, so the write-side
 * error advice in {@code adapter.in} can catch it without reaching into the domain layer (AD-1) —
 * the same pattern {@link RenameNotPermittedApplicationException} and {@code NotAMemberException}
 * established.
 *
 * <p>Reached only under an ACL/event-stream divergence: the ACL resolves the caller to a
 * {@link de.sgart.shared.MemberId} the household's stream never recorded joining, so
 * {@code resolve()} succeeds but the aggregate's own {@code requireMember} guard rejects the
 * command. Defense-in-depth, but a real path — hence a real translation rather than a leak.
 */
public final class NotAHouseholdMemberApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public NotAHouseholdMemberApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("identity.notAMember", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
