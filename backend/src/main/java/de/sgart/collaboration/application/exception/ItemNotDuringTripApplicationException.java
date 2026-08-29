package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.ItemNotDuringTripException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of {@link ItemNotDuringTripException} (Story 3.3, AC2–AC4,
 * AC6, Cl. 5) — raised by handlers for check-off, uncheck, postpone, postpone-to-list, and reroute
 * when the source list is no longer {@code IN_TRIP}. Replaces {@code
 * ItemNotReroutableApplicationException} (which named the reroute operation specifically) with a
 * gate-level name shared by all in-trip mutations so {@code adapter.in} never imports
 * {@code ..domain..} (CLAUDE.md §8). A {@code 409 Conflict} — the list is no longer In-Trip, so
 * no in-trip mutation may proceed (a state conflict, distinct from a permission failure).
 * Mirrors {@link TripNotStartableApplicationException}.
 */
public final class ItemNotDuringTripApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public ItemNotDuringTripApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("item.notDuringTrip", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
