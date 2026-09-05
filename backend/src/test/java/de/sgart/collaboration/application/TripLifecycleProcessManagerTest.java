package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.ShoppingTrip;
import de.sgart.collaboration.domain.event.TripCompleted;
import de.sgart.collaboration.domain.event.StoreAddedToTrip;
import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.collaboration.domain.event.TripStartedForList;
import de.sgart.collaboration.domain.event.TripCompletedForList;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
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
        // Fix 2 (review patch) — bounded retry: a concurrent writer lands on the trip stream
        // *between* the PM's read and its append, so the PM's first append conflicts and it must
        // reload → re-apply → append again. The decorator injects that race on the completion append
        // (not the start append), which is exactly the window the retry loop's catch branch guards.
        ConflictOnCompletionEventStore racingStore = new ConflictOnCompletionEventStore(eventStore);
        TripLifecycleProcessManager racingProcessManager = new TripLifecycleProcessManager(racingStore);
        racingProcessManager.onTripStartedForList(startedTrigger());

        TripCompletedForList completedForList = new TripCompletedForList(
                EventId.generate(), householdId, listId, tripId);
        racingProcessManager.onTripCompletedForList(completedForList);

        // The retry branch actually ran: the completion append was attempted twice (first conflicted,
        // second succeeded), the injected StoreAddedToTrip is present, and TripCompleted landed once.
        assertThat(racingStore.completionAppendAttempts).isEqualTo(2);
        List<DomainEvent> tripEvents = eventStore.readStream(tripStreamId);
        assertThat(tripEvents).anyMatch(e -> e instanceof StoreAddedToTrip);
        assertThat(tripEvents.stream().filter(e -> e instanceof TripCompleted).count()).isEqualTo(1);
    }

    /**
     * Test double that simulates a concurrent writer racing the completion reaction: the first time
     * the PM appends a {@link TripCompleted}, it first sneaks a {@link StoreAddedToTrip} onto the
     * trip stream at the PM's expected version, so the PM's own append conflicts and its bounded
     * retry loop is forced to reload and try again.
     */
    private static final class ConflictOnCompletionEventStore implements EventStore {

        private final EventStore delegate;
        private boolean injected;
        private int completionAppendAttempts;

        private ConflictOnCompletionEventStore(EventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
            boolean isCompletion = events.stream().anyMatch(event -> event instanceof TripCompleted);
            if (isCompletion) {
                completionAppendAttempts++;
                if (!injected) {
                    injected = true;
                    StreamId streamId = expectedVersion.streamId();
                    ShoppingTrip concurrent = ShoppingTrip.rehydrate(streamId, delegate.readStream(streamId));
                    concurrent.addStore(StoreId.generate(), CommandId.generate());
                    delegate.append(expectedVersion, concurrent.uncommittedEvents(), CommandId.generate());
                }
            }
            delegate.append(expectedVersion, events, commandId);
        }

        @Override
        public List<DomainEvent> readStream(StreamId streamId) {
            return delegate.readStream(streamId);
        }
    }
}
