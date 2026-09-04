package de.sgart.collaboration.domain.readmodel;

import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.TripId;
import java.util.List;

/**
 * Domain-owned port over the shopping-list CQRS read model (AD-4) — built solely by {@code
 * ShoppingListReadModelProjector} folding {@code ShoppingListCreated}/{@code ShoppingListRenamed};
 * a command handler never writes it. {@code ListOpenLists} (application layer) is the query that
 * reads through this port.
 *
 * <p>Exposes every list, not just {@code Open} ones — the AC2 ordinal counts a household's Open
 * (and later In-Trip) lists in creation order, so the query filters/derives from the full
 * creation-ordered sequence this port returns, and 2.2's Offen/Erledigt split reads through the
 * same port unchanged.
 */
public interface ShoppingListReadModel {

    /** @return the household's lists in creation order (oldest first), across every status. */
    List<ShoppingListView> listsOf(HouseholdId householdId);

    /**
     * Flips a list's status to {@code IN_TRIP} and records its active trip (Story 3.1 AC5; Story
     * 3.2 {@code active_trip_id}, Cl. 4) — written only by the projector. Defaulted (rather than a
     * bare abstract method) so the many read-only query tests that supply this port as a {@code
     * listsOf}-only lambda keep compiling; a real implementation always overrides it.
     */
    default void markInTrip(ShoppingListId listId, TripId tripId) {
        throw new UnsupportedOperationException("markInTrip is not implemented by this read model");
    }

    /**
     * Flips a list's status to {@code DONE} and clears its {@code active_trip_id} (Story 3.4, AC7,
     * Cl. 5) — written only by the projector on {@code TripCompletedForList}. The list leaves the
     * Open/In-Trip set and the {@code ListDoneLists} archive picks it up. Defaulted so read-only
     * query tests that supply this port as a {@code listsOf}-only lambda keep compiling; a real
     * implementation always overrides it. Mirrors {@link #markInTrip}.
     */
    default void markDone(ShoppingListId listId) {
        throw new UnsupportedOperationException("markDone is not implemented by this read model");
    }
}
