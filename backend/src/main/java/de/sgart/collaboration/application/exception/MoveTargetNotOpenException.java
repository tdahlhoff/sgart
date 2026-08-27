package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.MoveItemHandler;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised by {@link MoveItemHandler} when the move's target list is not {@link
 * de.sgart.collaboration.domain.ListStatus#OPEN} (Story 2.4, AC5, E3) — a clean {@code 409
 * Conflict}, checked before the source is mutated so a stale client can never strand an item by
 * moving it onto a non-Open list. Lives here, not in {@code collaboration.domain}, mirroring
 * {@link DuplicateItemApplicationException} — the target's status is read by the handler (this
 * aggregate does not own the target, AD-10), so there is no matching domain exception to translate.
 */
public final class MoveTargetNotOpenException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public MoveTargetNotOpenException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("list.moveTargetNotOpen", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
