package de.sgart.collaboration.domain;

import de.sgart.collaboration.domain.event.StoreAddedToTrip;
import de.sgart.collaboration.domain.event.TripCompleted;
import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.collaboration.domain.exception.TripNotActiveException;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventSourcedAggregate;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SGART's third aggregate in the Collaboration context (Story 3.1, after {@code Household} and
 * {@code ShoppingList}, AD-3): a household's shopping trip, started against one list across one or
 * more stores. Created by the {@code TripStartProcessManager} reacting to the list's {@code
 * TripStartedForList} (Cl. 1) — never directly by a handler. References its list and stores
 * <strong>by id only</strong> (AR2), never loading either. State changes only through {@link
 * #apply(DomainEvent)}, folding {@link TripStarted} (the {@link EventSourcedAggregate} contract).
 *
 * <p>Minimal in 3.1 (Cl. 8): only {@link TripStatus#ACTIVE} is reachable — {@code DONE} is Story
 * 3.4's completion transition. In-trip mutations (check/reroute/postpone/complete) are Stories
 * 3.2–3.4.
 */
public final class ShoppingTrip extends EventSourcedAggregate {

    private TripId tripId;
    private HouseholdId householdId;
    private ShoppingListId listId;
    private List<StoreId> storeIds;
    private TripStatus status;

    private ShoppingTrip(StreamId streamId) {
        super(streamId);
    }

    /**
     * Creates a brand-new trip on its own stream (AC1) — driven by the {@code
     * TripStartProcessManager}, never a handler directly.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here;
     *     idempotency is the {@code EventStore}'s concern, not the aggregate's
     */
    public static ShoppingTrip start(
            TripId tripId,
            HouseholdId householdId,
            ShoppingListId listId,
            List<StoreId> storeIds,
            CommandId commandId) {
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(storeIds, "storeIds must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        if (storeIds.isEmpty()) {
            throw new IllegalArgumentException("storeIds must not be empty");
        }

        ShoppingTrip trip = new ShoppingTrip(StreamId.forTrip(tripId));
        trip.raise(new TripStarted(EventId.generate(), tripId, householdId, listId, storeIds));
        return trip;
    }

    /** Rebuilds a trip from its persisted event history. */
    public static ShoppingTrip rehydrate(StreamId streamId, List<? extends DomainEvent> history) {
        ShoppingTrip trip = new ShoppingTrip(streamId);
        trip.replay(history);
        return trip;
    }

    public TripId tripId() {
        return tripId;
    }

    public HouseholdId householdId() {
        return householdId;
    }

    public ShoppingListId listId() {
        return listId;
    }

    public List<StoreId> storeIds() {
        return List.copyOf(storeIds);
    }

    public TripStatus status() {
        return status;
    }

    /**
     * Completes the trip (Story 3.4, AC4, Cl. 3) — driven by the {@code
     * TripLifecycleProcessManager} reacting to {@code TripCompletedForList} (AD-10), never a
     * handler directly. Folds the trip {@code ACTIVE → DONE}. An already-{@code DONE} trip is a
     * convergent no-op (raises nothing) — idempotent re-delivery of the same {@code
     * TripCompletedForList} must not double-complete (mirrors {@link #addStore}'s ACTIVE guard).
     *
     * @param commandId deterministically derived from the triggering event id (AD-8)
     */
    public void complete(CommandId commandId) {
        Objects.requireNonNull(commandId, "commandId must not be null");

        if (status == TripStatus.DONE) {
            return; // convergent no-op — already DONE (AD-8)
        }
        raise(new TripCompleted(EventId.generate(), tripId, householdId, listId));
    }

    /**
     * Adds a store to the trip spontaneously (Story 3.2, AC3) — the trip's <strong>first in-trip
     * mutation</strong>. Permitted only while {@link TripStatus#ACTIVE} ({@code DONE} is
     * reachable since Story 3.4, so this guard is enforced). A store already in the trip is a
     * convergent no-op (raises nothing, AD-8). Does <strong>not</strong> validate that {@code
     * storeId} exists in the household — {@code Store} is an entity inside the separate {@code
     * Household} aggregate this root never loads or mutates (AD-3, mirrors {@link
     * ShoppingList#assignItemToStore}).
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void addStore(StoreId storeId, CommandId commandId) {
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");

        if (status != TripStatus.ACTIVE) {
            throw new TripNotActiveException("A store may only be added to an Active trip, trip is " + status);
        }
        if (storeIds.contains(storeId)) {
            return; // convergent no-op — the store is already part of the trip (AD-8)
        }
        raise(new StoreAddedToTrip(EventId.generate(), tripId, householdId, storeId));
    }

    @Override
    protected void apply(DomainEvent event) {
        switch (event) {
            case TripStarted started -> {
                this.tripId = started.tripId();
                this.householdId = started.householdId();
                this.listId = started.listId();
                this.storeIds = List.copyOf(started.storeIds());
                this.status = TripStatus.ACTIVE;
            }
            case StoreAddedToTrip added -> {
                List<StoreId> updated = new ArrayList<>(storeIds);
                updated.add(added.storeId());
                this.storeIds = List.copyOf(updated);
            }
            case TripCompleted ignored -> this.status = TripStatus.DONE;
            default -> throw new IllegalArgumentException(
                    "ShoppingTrip cannot apply unknown event type: " + event.getClass());
        }
    }
}
