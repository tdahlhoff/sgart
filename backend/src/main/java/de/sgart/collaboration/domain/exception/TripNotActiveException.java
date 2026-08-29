package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.ShoppingTrip;
import de.sgart.collaboration.domain.TripStatus;

/**
 * Raised by {@link ShoppingTrip#addStore} when the trip is not {@link TripStatus#ACTIVE} (Story
 * 3.2, AC3, AC5) — a store may only be added to an active trip. {@code DONE} is unreachable until
 * Story 3.4, so this guard is defensive, mirroring {@code ShoppingList}'s restraint around its own
 * not-yet-reachable {@code DONE} branches.
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}
 * (AD-1). The application layer translates it into a client-localizable code (a {@code 409}).
 */
public final class TripNotActiveException extends RuntimeException {

    public TripNotActiveException(String message) {
        super(message);
    }
}
