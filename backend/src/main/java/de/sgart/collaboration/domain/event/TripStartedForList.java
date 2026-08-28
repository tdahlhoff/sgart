package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import java.util.List;
import java.util.Objects;

/**
 * A trip was started against a list (Story 3.1, AC1). Raised on the list's own {@code list-{id}}
 * stream — the single guarded append of a trip start (Cl. 1) — and folds {@link ShoppingList} from
 * {@code OPEN} to {@code IN_TRIP} there. Carries {@code tripId}/{@code storeIds} as the payload the
 * {@code TripStartProcessManager} needs to create the {@code ShoppingTrip} aggregate (Cl. 1/5), not
 * because the list reasons over stores — mirrors {@link ItemMovedToList} carrying the target-side
 * payload for its process manager.
 *
 * <p>Carries no personal data and no <em>who</em> (AD-5/AD-6; no audit trail, mirrors {@link
 * ItemAdded}) — household/list/trip/store ids only.
 */
public record TripStartedForList(
        EventId eventId,
        HouseholdId householdId,
        ShoppingListId listId,
        TripId tripId,
        List<StoreId> storeIds)
        implements DomainEvent {

    public TripStartedForList {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(storeIds, "storeIds must not be null");
        // A trip's stores are a set — dedupe so a client-crafted request cannot persist the same
        // store twice (the Flutter picker keys off a Set, but the API contract must defend it),
        // which would otherwise surface as duplicate store groups in the Story 3.2 trip view.
        storeIds = storeIds.stream().distinct().toList();
        if (storeIds.isEmpty()) {
            throw new IllegalArgumentException("storeIds must not be empty");
        }
    }
}
