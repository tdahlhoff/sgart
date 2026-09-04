package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.TripId;
import java.util.Objects;

/**
 * A trip was completed against a list (Story 3.4, AC4, Cl. 2/3). Raised on the list's own {@code
 * list-{id}} stream by {@link ShoppingList#completeTrip} after the {@code OPEN}-item sweep — the
 * completion counterpart of {@link TripStartedForList}. Folds the list {@code IN_TRIP → DONE} and
 * carries {@code tripId} so the {@code TripLifecycleProcessManager} can complete the matching {@link
 * de.sgart.collaboration.domain.ShoppingTrip} aggregate (Cl. 3). The list becomes immutable once
 * {@code DONE} — all planning commands already {@code requireOpen()}, all in-trip commands
 * {@code requireInTrip()}, and {@code rename} rejects a {@code DONE} list.
 *
 * <p>Carries no personal data and no <em>who</em> (AD-5/AD-6, mirrors {@link TripStartedForList})
 * — household/list/trip ids only.
 */
public record TripCompletedForList(
        EventId eventId, HouseholdId householdId, ShoppingListId listId, TripId tripId)
        implements DomainEvent {

    public TripCompletedForList {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(tripId, "tripId must not be null");
    }
}
