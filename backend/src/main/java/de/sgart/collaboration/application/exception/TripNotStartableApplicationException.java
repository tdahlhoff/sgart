package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.collaboration.domain.exception.TripNotStartableException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of {@link TripNotStartableException} (Story 3.1, AC2) —
 * {@link StartTripHandler} maps the domain guard into this exception at the seam so {@code
 * adapter.in} never imports {@code ..domain..} (CLAUDE.md §8). A clean {@code 409 Conflict}: the
 * list is not Open, so no trip may start. Mirrors {@link ItemChangeNotPermittedApplicationException}.
 */
public final class TripNotStartableApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public TripNotStartableApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("trip.notStartable", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
