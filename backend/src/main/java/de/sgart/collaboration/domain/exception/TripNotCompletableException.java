package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;

/**
 * Raised by {@link ShoppingList#completeTrip} when the list is not {@link ListStatus#IN_TRIP}
 * (Story 3.4, AC8, Cl. 8) — a trip may only be completed from an In-Trip list, so an {@code OPEN}
 * or {@code DONE} list refuses completion. Mirrors {@link TripNotStartableException} (the inverse
 * transition). A plain domain exception carrying no client-facing {@code code}/{@code
 * ErrorDescriptor} (AD-1). The application layer translates it into a client-localizable code (a
 * {@code 409}).
 */
public final class TripNotCompletableException extends RuntimeException {

    public TripNotCompletableException(String message) {
        super(message);
    }
}
