package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.MoveItemHandler;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised by {@link MoveItemHandler} when the move's target list equals its source list (Story 2.4,
 * AC5) — a fail-fast {@code 400}, checked before any aggregate is loaded. A request-envelope error
 * like {@link InvalidCommandEnvelopeException}, not a domain error: the caller sent a nonsensical
 * request, not an invalid list.
 */
public final class InvalidMoveTargetException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidMoveTargetException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("list.moveTargetSameAsSource", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
