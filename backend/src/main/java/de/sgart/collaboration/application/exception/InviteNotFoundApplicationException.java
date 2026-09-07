package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.InviteNotFoundException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@link InviteNotFoundException} into a stable,
 * client-localizable {@code invite.notFound} code — a {@code 404 Not Found} (Story 4.2, AC5).
 * Mirrors {@code DuplicatePendingInviteApplicationException}; lives here, not in {@code
 * collaboration.domain}, so the write-side error advice in {@code adapter.in} can catch it without
 * reaching into the domain layer (AD-1).
 */
public final class InviteNotFoundApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InviteNotFoundApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("invite.notFound", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
