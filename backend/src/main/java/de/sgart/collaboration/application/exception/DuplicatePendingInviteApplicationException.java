package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.DuplicatePendingInviteException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@code DuplicatePendingInviteException} into a
 * stable, client-localizable {@code invite.duplicatePending} code — a {@code 409 Conflict} (Story
 * 4.1, AC2). Mirrors {@link DuplicateStoreNameApplicationException}; lives here, not in {@code
 * collaboration.domain}, so the write-side error advice in {@code adapter.in} can catch it without
 * reaching into the domain layer (AD-1).
 */
public final class DuplicatePendingInviteApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public DuplicatePendingInviteApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("invite.duplicatePending", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
