package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingTrip;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import java.util.Objects;

/**
 * A store was added to an active trip spontaneously (Story 3.2, AC3) — the trip's <strong>first
 * in-trip mutation</strong>, raised on the trip's own {@code trip-{id}} stream by {@link
 * ShoppingTrip#addStore}. Carries the store <strong>by id</strong> (AR2) — this aggregate never
 * validates the store exists, mirroring {@link TripStarted} not validating its initial store set.
 *
 * <p>Carries no personal data and no <em>who</em> (AD-5/AD-6, mirrors {@link TripStarted}) — a
 * household/trip/store id, never a person.
 */
public record StoreAddedToTrip(EventId eventId, TripId tripId, HouseholdId householdId, StoreId storeId)
        implements DomainEvent {

    public StoreAddedToTrip {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
    }
}
