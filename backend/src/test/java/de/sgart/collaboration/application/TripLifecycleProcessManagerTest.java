package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.ShoppingTrip;
import de.sgart.collaboration.domain.event.TripCompleted;
import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.collaboration.domain.event.TripStartedForList;
import de.sgart.collaboration.domain.event.TripCompletedForList;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} only, no framework or persistence (CLAUDE.md §6).
 * Proves SGART's second process manager (Story 3.1/3.4, AD-10): a triggering {@code
 * TripStartedForList} creates the {@code ShoppingTrip} on {@code trip-{id}} with the right list
 * and stores, and re-processing the same event (replay) creates nothing new (idempotent — the
 * derived command id + converge-on-conflict). A triggering {@code TripCompletedForList} raises
 * {@code TripCompleted} on the same trip stream, and re-processing the same event is likewise a
 * no-op.
 */
class TripLifecycleProcessManagerTest {

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final TripLifecycleProcessManager processManager = new TripLifecycleProcessManager(eventStore);

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final TripId tripId = TripId.generate();
    private final StreamId tripStreamId = StreamId.forTrip(tripId);

    private TripStartedForList startedTrigger() {
        return new TripStartedForList(
                EventId.generate(), householdId, listId, tripId, List.of(StoreId.generate(), StoreId.generate()));
    }

    // ── onTripStartedForList ──────────────────────────────────────────────────────────────────────

    @Test
    void createsTheTripOnItsOwnStreamWithTheListAndStores() {
        TripStartedForList started = startedTrigger();

        processManager.onTripStartedForList(started);

        List<DomainEvent> tripEvents = eventStore.readStream(tripStreamId);
        assertThat(tripEvents).hasSize(1);
        assertThat(tripEvents.get(0)).isInstanceOf(TripStarted.class);
        TripStarted trip = (TripStarted) tripEvents.get(0);
        assertThat(trip.tripId()).isEqualTo(tripId);
        assertThat(trip.householdId()).isEqualTo(householdId);
        assertThat(trip.listId()).isEqualTo(listId);
        assertThat(trip.storeIds()).isEqualTo(started.storeIds());
    }

    @Test
    void replayingTheSameTripStartedForListIsANoOp() {
        TripStartedForList started = startedTrigger();

        processManager.onTripStartedForList(started);
        processManager.onTripStartedForList(started);

        assertThat(eventStore.readStream(tripStreamId)).hasSize(1);
    }

    // ── onTripCompletedForList ────────────────────────────────────────────────────────────────────

    @Test
    void completingTheTripRaisesTripCompletedOnTheTripStream() {
        processManager.onTripStartedForList(startedTrigger());

        TripCompletedForList completed = new TripCompletedForList(
                EventId.generate(), householdId, listId, tripId);
        processManager.onTripCompletedForList(completed);

        List<DomainEvent> tripEvents = eventStore.readStream(tripStreamId);
        assertThat(tripEvents).hasSize(2);
        assertThat(tripEvents.get(1)).isInstanceOf(TripCompleted.class);
        TripCompleted tripCompleted = (TripCompleted) tripEvents.get(1);
        assertThat(tripCompleted.tripId()).isEqualTo(tripId);
    }

    @Test
    void replayingTheSameTripCompletedForListIsANoOp() {
        processManager.onTripStartedForList(startedTrigger());

        TripCompletedForList completed = new TripCompletedForList(
                EventId.generate(), householdId, listId, tripId);
        processManager.onTripCompletedForList(completed);
        processManager.onTripCompletedForList(completed);

        assertThat(eventStore.readStream(tripStreamId)).hasSize(2);
    }

    @Test
    void aLostRaceOnCompletionConvergesOnRetry() {
        // Fix 2 (review patch) — bounded retry: if another event is appended to the trip stream
        // between the PM's read and its append, the PM retries and still lands TripCompleted.
        processManager.onTripStartedForList(startedTrigger());

        // Simulate a concurrent StoreAddedToTrip append racing the completion reaction:
        // reload the trip, derive a store-add event, and append it to advance the stream version.
        ShoppingTrip trip = ShoppingTrip.rehydrate(tripStreamId, eventStore.readStream(tripStreamId));
        // Append a synthetic extra event at the current (post-start) version to force a conflict.
        ShoppingTrip concurrentStore = ShoppingTrip.rehydrate(tripStreamId, eventStore.readStream(tripStreamId));
        concurrentStore.addStore(StoreId.generate(), CommandId.generate());
        eventStore.append(trip.version(), concurrentStore.uncommittedEvents(), CommandId.generate());

        TripCompletedForList completedForList = new TripCompletedForList(
                EventId.generate(), householdId, listId, tripId);
        processManager.onTripCompletedForList(completedForList);

        List<DomainEvent> tripEvents = eventStore.readStream(tripStreamId);
        assertThat(tripEvents.stream().filter(e -> e instanceof TripCompleted).count()).isEqualTo(1);
    }
}
