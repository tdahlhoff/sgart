package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.CompleteTripHandler;
import de.sgart.collaboration.domain.exception.TripNotCompletableException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of {@link TripNotCompletableException} (Story 3.4, AC8, Cl. 8)
 * — {@link CompleteTripHandler} maps the domain guard into this exception at the seam so {@code
 * adapter.in} never imports {@code ..domain..} (CLAUDE.md §8). A clean {@code 409 Conflict}: the
 * list is not In-Trip, so no trip may complete. Mirrors {@link TripNotStartableApplicationException}.
 */
public final class TripNotCompletableApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public TripNotCompletableApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("trip.notCompletable", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
