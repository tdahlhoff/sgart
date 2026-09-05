package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;

/**
 * Raised by {@link ShoppingList#requireInTrip()} when the list is not {@link ListStatus#IN_TRIP}
 * (Story 3.3, AC2–AC4, AC6, Cl. 5) — items may only be checked off, unchecked, discarded, postponed to another list, or
 * rerouted during an active trip; the trip may have completed concurrently (Story 3.4), so this is
 * a state conflict, not a permission failure. Replaces {@code ItemNotReroutableException} (which
 * named the reroute operation specifically — wrong for check-off/postpone) with a gate-level name
 * shared by all in-trip mutations (DRY/Boy Scout). Mirrors {@link TripNotStartableException}.
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}
 * (AD-1). The application layer translates it into a client-localizable code (a {@code 409}).
 */
public final class ItemNotDuringTripException extends RuntimeException {

    public ItemNotDuringTripException(String message) {
        super(message);
    }
}
