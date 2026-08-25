package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.DuplicateStoreNameException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@code DuplicateStoreNameException} into a
 * stable, client-localizable {@code store.duplicateName} code — a {@code 409 Conflict} (Story 1.8,
 * AC1). A uniqueness collision is a conflict, not a malformed request, so it maps to {@code 409}
 * rather than {@code 400}. Lives here, not in {@code collaboration.domain}, so the write-side error
 * advice in {@code adapter.in} can catch it without reaching into the domain layer (the same
 * pattern {@link RenameNotPermittedApplicationException} established).
 */
public final class DuplicateStoreNameApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public DuplicateStoreNameApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("store.duplicateName", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
