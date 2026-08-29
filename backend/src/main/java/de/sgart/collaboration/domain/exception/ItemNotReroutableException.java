package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;

/**
 * Raised by {@link ShoppingList#rerouteItem} when the list is not {@link ListStatus#IN_TRIP}
 * (Story 3.2, AC2, AC5, Cl. 8) — an item may only be rerouted during an active trip; the trip may
 * have completed concurrently (Story 3.4), so this is a state conflict, not a permission failure.
 * Mirrors {@link TripNotStartableException}.
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}
 * (AD-1). The application layer translates it into a client-localizable code (a {@code 409}).
 */
public final class ItemNotReroutableException extends RuntimeException {

    public ItemNotReroutableException(String message) {
        super(message);
    }
}
