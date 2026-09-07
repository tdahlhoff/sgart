package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.LastAdminException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@link LastAdminException} into a stable,
 * client-localizable {@code membership.lastAdmin} code — a {@code 409 Conflict} (Story 4.3, AC5).
 * Lives here, not in {@code collaboration.domain}, so the write-side error advice in {@code
 * adapter.in} can catch it without reaching into the domain layer (AD-1).
 */
public final class LastAdminApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public LastAdminApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("membership.lastAdmin", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
