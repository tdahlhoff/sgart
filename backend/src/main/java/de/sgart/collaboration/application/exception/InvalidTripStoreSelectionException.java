package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised by {@link StartTripHandler} when the start-trip command carries zero stores (Story 3.1,
 * AC3) — a fail-fast {@code 400}, checked before any aggregate is loaded (mirrors {@link
 * InvalidMoveTargetException}). A request-envelope error, not a domain error: the caller sent a
 * nonsensical selection, not an invalid list.
 */
public final class InvalidTripStoreSelectionException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidTripStoreSelectionException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("trip.storeSelectionRequired", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
