package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.ShoppingTrip;
import de.sgart.collaboration.domain.event.TripCompletedForList;
import de.sgart.collaboration.domain.event.TripStartedForList;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.EventStore;
import de.sgart.shared.StreamId;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SGART's second process manager (Story 3.1/3.4, AD-10) — reacts to both {@link TripStartedForList}
 * and {@link TripCompletedForList} (raised on {@code list-{id}} streams) to manage the {@link
 * ShoppingTrip} aggregate's lifecycle on its own {@code trip-{id}} stream. Named
 * {@code TripLifecycleProcessManager} (Boy Scout, Story 3.4, Cl. 3) because it now spans both the
 * start and the completion transition.
 *
 * <p>On {@link TripStartedForList}: creates the {@link ShoppingTrip} aggregate exactly once (the
 * story 3.1 "start" reaction). On {@link TripCompletedForList}: completes the aggregate exactly
 * once (the story 3.4 "completion" reaction), mirroring the start reaction symmetrically.
 *
 * <p><strong>Exactly-once (retro Action 6/12):</strong> both reactions derive the trip command id
 * deterministically from the triggering event's id ({@link
 * CommandId#deterministicFrom(de.sgart.shared.EventId)}), so re-processing the same event on a
 * subscription restart or catch-up replay derives the same command id and is a no-op. A {@link
 * ConcurrencyConflictException} is caught and treated as converged. Never call {@link
 * CommandId#generate()} here — that would double-create/double-complete on replay.
 *
 * <p>Infra-free and {@code InMemoryEventStore}-testable, mirroring {@link ItemMoveProcessManager}.
 */
public final class TripLifecycleProcessManager {

    private static final Logger log = LoggerFactory.getLogger(TripLifecycleProcessManager.class);

    private final EventStore eventStore;

    public TripLifecycleProcessManager(EventStore eventStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
    }

    /** Reacts to one {@link TripStartedForList}, creating the trip exactly once. */
    public void onTripStartedForList(TripStartedForList started) {
        Objects.requireNonNull(started, "started must not be null");

        CommandId derivedCommandId = CommandId.deterministicFrom(started.eventId());
        ShoppingTrip trip = ShoppingTrip.start(
                started.tripId(),
                started.householdId(),
                started.listId(),
                started.storeIds(),
                derivedCommandId);

        try {
            eventStore.append(
                    AggregateVersion.initial(StreamId.forTrip(started.tripId())),
                    trip.uncommittedEvents(),
                    derivedCommandId);
        } catch (ConcurrencyConflictException alreadyCreated) {
            // Redelivery of the same trigger — the trip was already created on an earlier pass
            // (exactly-once via the deterministic command id). Convergent success, not an error.
            log.debug(
                    "TripLifecycleProcessManager: trip {} already created, treating as converged",
                    started.tripId());
        }
    }

    /** Reacts to one {@link TripCompletedForList}, completing the trip exactly once. */
    public void onTripCompletedForList(TripCompletedForList completed) {
        Objects.requireNonNull(completed, "completed must not be null");

        CommandId derivedCommandId = CommandId.deterministicFrom(completed.eventId());
        StreamId tripStreamId = StreamId.forTrip(completed.tripId());

        List<de.sgart.shared.DomainEvent> history = eventStore.readStream(tripStreamId);
        ShoppingTrip trip = ShoppingTrip.rehydrate(tripStreamId, history);
        AggregateVersion expectedVersion = trip.version();
        trip.complete(derivedCommandId);

        if (!trip.uncommittedEvents().isEmpty()) {
            try {
                eventStore.append(expectedVersion, trip.uncommittedEvents(), derivedCommandId);
            } catch (ConcurrencyConflictException alreadyCompleted) {
                // Redelivery racing itself — the trip was already completed on an earlier pass
                // (exactly-once via the deterministic command id). Convergent success, not an error.
                log.debug(
                        "TripLifecycleProcessManager: trip {} already completed, treating as converged",
                        completed.tripId());
            }
        }
    }
}
