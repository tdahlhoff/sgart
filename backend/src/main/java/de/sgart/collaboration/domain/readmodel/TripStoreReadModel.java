package de.sgart.collaboration.domain.readmodel;

import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import java.util.List;

/**
 * Domain-owned port over the trip-store CQRS read model (AD-4, Story 3.2, Cl. 4) — built solely by
 * {@code ShoppingTripReadModelProjector} folding {@code TripStarted}/{@code StoreAddedToTrip}; a
 * command handler never writes it. {@code TripView} (application layer) is the query that reads
 * through this port. Mirrors {@link ItemReadModel}'s shape.
 */
public interface TripStoreReadModel {

    /**
     * Idempotent upsert — the projector's {@code TripStarted}/{@code StoreAddedToTrip} write. A
     * store already recorded for the trip is a no-op (so {@code sequence_number} stays stable
     * across replay).
     */
    void addStore(TripId tripId, StoreId storeId);

    /** @return the trip's stores in add order (oldest first). */
    List<StoreId> storesOf(TripId tripId);
}
