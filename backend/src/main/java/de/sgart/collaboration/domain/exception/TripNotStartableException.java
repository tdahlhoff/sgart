package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;

/**
 * Raised by {@link ShoppingList#startTrip} when the list is not {@link ListStatus#OPEN} (Story
 * 3.1, AC2) — a trip may only start from an Open list, so a list already {@code IN_TRIP} or {@code
 * DONE} refuses a second start. This is the atomic "at most one Active trip per list" guard, since
 * it is the list stream's own expected-version append (AD-8, Cl. 1). Mirrors {@link
 * ItemChangeNotPermittedException}.
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}
 * (AD-1). The application layer translates it into a client-localizable code (a {@code 409}).
 */
public final class TripNotStartableException extends RuntimeException {

    public TripNotStartableException(String message) {
        super(message);
    }
}
