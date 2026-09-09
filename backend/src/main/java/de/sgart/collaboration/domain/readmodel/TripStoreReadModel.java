package de.sgart.collaboration.domain.readmodel;

import de.sgart.shared.HouseholdId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import java.util.List;
import java.util.Optional;

/**
 * Domain-owned port over the trip-store CQRS read model (AD-4, Story 3.2, Cl. 4) — built solely by
 * {@code ShoppingTripReadModelProjector} folding {@code TripStarted}/{@code StoreAddedToTrip}; a
 * command handler never writes it. {@code TripView} (application layer) is the query that reads
 * through this port. Mirrors {@link ItemReadModel}'s shape. {@code householdId} is carried
 * alongside each row (Story 4.3) solely so the delete-cascade purge can target a household's trip
 * rows directly — otherwise undiscoverable once {@code shopping_list_read_model}'s rows for the
 * same household have already been purged (the two purges are independent, unordered projections).
 */
public interface TripStoreReadModel {

    /**
     * Idempotent upsert — the projector's {@code TripStarted}/{@code StoreAddedToTrip} write. A
     * store already recorded for the trip is a no-op (so {@code sequence_number} stays stable
     * across replay).
     */
    void addStore(HouseholdId householdId, TripId tripId, StoreId storeId);

    /** @return the trip's stores in add order (oldest first). */
    List<StoreId> storesOf(TripId tripId);

    /**
     * Removes all store rows for a completed trip (Story 3.4, Cl. 6) — written only by the
     * projector on {@code TripCompleted}. The trip is done; its store rows are no longer needed and
     * would prevent a clean read model. Idempotent: re-projecting the same {@code TripCompleted} is
     * a safe no-op (DELETE WHERE on a missing set). Mirrors {@link #addStore}.
     */
    void deleteForTrip(TripId tripId);

    /**
     * Resolves the household a trip belongs to (Story 4.4, T4) — the live-sync fan-out's
     * stream-name resolver uses this for {@code trip-} stream events, none of which carry a
     * {@code householdId} field directly resolvable without a row lookup. Empty when the trip has
     * no store rows (yet) projected — a projector-race edge the caller skips rather than fails on.
     * Defaulted so test doubles keep compiling; a real implementation always overrides it.
     */
    default Optional<HouseholdId> householdIdOfTrip(TripId tripId) {
        throw new UnsupportedOperationException("householdIdOfTrip is not implemented by this read model");
    }
}
