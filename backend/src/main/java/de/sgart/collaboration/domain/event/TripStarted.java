package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingTrip;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import java.util.List;
import java.util.Objects;

/**
 * The marquee trip event (Story 3.1, AC1) — raised on the trip's own {@code trip-{id}} stream by
 * the {@code TripStartProcessManager} reacting to {@link TripStartedForList} (Cl. 1). The {@link
 * ShoppingTrip} aggregate's sole state-producing event in 3.1; folds the new aggregate to {@code
 * ACTIVE} with its linked list and store set.
 *
 * <p>Carries no personal data and no <em>who</em> (AD-5/AD-6; no audit trail, mirrors {@link
 * ShoppingListCreated}) — household/list/trip/store ids only.
 */
public record TripStarted(
        EventId eventId,
        TripId tripId,
        HouseholdId householdId,
        ShoppingListId listId,
        List<StoreId> storeIds)
        implements DomainEvent {

    public TripStarted {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(storeIds, "storeIds must not be null");
        // A trip's stores are a set — dedupe defensively (the same invariant TripStartedForList
        // enforces on the list side) so a duplicate store id can never reach the ShoppingTrip fold.
        storeIds = storeIds.stream().distinct().toList();
        if (storeIds.isEmpty()) {
            throw new IllegalArgumentException("storeIds must not be empty");
        }
    }
}
