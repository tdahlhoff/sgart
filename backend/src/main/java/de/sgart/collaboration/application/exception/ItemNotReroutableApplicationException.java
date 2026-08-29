package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.RerouteItemHandler;
import de.sgart.collaboration.domain.exception.ItemNotReroutableException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of {@link ItemNotReroutableException} (Story 3.2, AC2, AC5,
 * Cl. 8) — {@link RerouteItemHandler} maps the domain guard into this exception at the seam so
 * {@code adapter.in} never imports {@code ..domain..} (CLAUDE.md §8). A {@code 409 Conflict} — the
 * list is no longer In-Trip, so no item may be rerouted (a state conflict, distinct from the
 * off-trip assign's 403). Mirrors {@link TripNotStartableApplicationException}.
 */
public final class ItemNotReroutableApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public ItemNotReroutableApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("item.notReroutable", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
