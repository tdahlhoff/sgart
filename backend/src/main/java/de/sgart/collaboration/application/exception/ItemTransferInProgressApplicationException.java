package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.ItemTransferInProgressException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of {@link ItemTransferInProgressException} (Story 3.6, AC4) —
 * raised by every item-mutating handler when the item is currently reserved by a pending transfer,
 * so {@code adapter.in} never imports {@code ..domain..} (CLAUDE.md §8). A {@code 409 Conflict} —
 * the item is mid-transfer, so no other mutation may proceed until the saga resolves. Mirrors
 * {@link ItemNotDuringTripApplicationException}.
 */
public final class ItemTransferInProgressApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public ItemTransferInProgressApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("item.transferInProgress", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
