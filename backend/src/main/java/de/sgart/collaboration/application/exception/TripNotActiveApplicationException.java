package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.AddStoreToTripHandler;
import de.sgart.collaboration.domain.exception.TripNotActiveException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of {@link TripNotActiveException} (Story 3.2, AC3, AC5) —
 * {@link AddStoreToTripHandler} maps the domain guard into this exception at the seam so {@code
 * adapter.in} never imports {@code ..domain..} (CLAUDE.md §8). A {@code 409 Conflict}; defensive
 * only — {@code DONE} is unreachable until Story 3.4. Mirrors {@link
 * TripNotStartableApplicationException}.
 */
public final class TripNotActiveApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public TripNotActiveApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("trip.notActive", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
