package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.DuplicateItemException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@link DuplicateItemException} into a stable,
 * client-localizable {@code item.duplicate} code — a {@code 409 Conflict} (Story 2.3, AC2/AC3).
 * Lives here, not in {@code collaboration.domain}, so the write-side error advice in {@code
 * adapter.in} can catch it without reaching into the domain layer, mirroring {@link
 * DuplicateStoreNameApplicationException}.
 */
public final class DuplicateItemApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public DuplicateItemApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("item.duplicate", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
