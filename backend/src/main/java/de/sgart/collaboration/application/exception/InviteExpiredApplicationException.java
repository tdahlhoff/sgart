package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.InviteExpiredException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@link InviteExpiredException} into a stable,
 * client-localizable {@code invite.expired} code — a {@code 410 Gone} (Story 4.2, AC3): the invite
 * resource is no longer available, whether its expiry was just lazily discovered or it was already
 * {@code EXPIRED}. Mirrors {@code DuplicatePendingInviteApplicationException}; lives here, not in
 * {@code collaboration.domain}, so the write-side error advice in {@code adapter.in} can catch it
 * without reaching into the domain layer (AD-1).
 */
public final class InviteExpiredApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InviteExpiredApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("invite.expired", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
