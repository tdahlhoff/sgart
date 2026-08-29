package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.AddStoreToTripHandler;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised by {@link AddStoreToTripHandler} when the target {@code tripId} has no stream (never
 * started) — a clean {@code 404} rather than mutating a phantom aggregate. Also raised, defense-in-
 * depth, when a trip's loaded {@code householdId} does not match the request path's household: a
 * trip under a different household is treated the same as an unknown one, so the response never
 * confirms a trip's existence under a household the caller did not ask about. Mirrors {@link
 * ShoppingListNotFoundException}.
 */
public final class TripNotFoundException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public TripNotFoundException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("trip.notFound", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
