package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingTrip;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.TripId;
import java.util.Objects;

/**
 * A trip was completed — its status transitions to {@code DONE} (Story 3.4, AC4, Cl. 3). Raised on
 * the trip's own {@code trip-{id}} stream by the {@code TripLifecycleProcessManager} reacting to
 * {@link TripCompletedForList} — never by a command handler directly (AD-10), mirroring how {@link
 * TripStarted} is raised by the process manager reacting to {@link TripStartedForList}. The
 * completion counterpart of {@link TripStarted}; folds the trip {@code ACTIVE → DONE}.
 *
 * <p>Carries no personal data and no <em>who</em> (AD-5/AD-6, mirrors {@link TripStarted}) —
 * trip/household/list ids only, never a person.
 */
public record TripCompleted(
        EventId eventId, TripId tripId, HouseholdId householdId, ShoppingListId listId)
        implements DomainEvent {

    public TripCompleted {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
    }
}
