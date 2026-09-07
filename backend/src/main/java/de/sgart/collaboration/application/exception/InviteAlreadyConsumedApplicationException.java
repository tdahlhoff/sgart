package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.InviteAlreadyConsumedException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@link InviteAlreadyConsumedException} into a
 * stable, client-localizable {@code invite.alreadyUsed} code — a {@code 409 Conflict} (Story 4.2,
 * AC5): a spent personal invite link cannot be ridden by a stranger. Mirrors {@code
 * DuplicatePendingInviteApplicationException}; lives here, not in {@code collaboration.domain}, so
 * the write-side error advice in {@code adapter.in} can catch it without reaching into the domain
 * layer (AD-1).
 */
public final class InviteAlreadyConsumedApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InviteAlreadyConsumedApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("invite.alreadyUsed", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
