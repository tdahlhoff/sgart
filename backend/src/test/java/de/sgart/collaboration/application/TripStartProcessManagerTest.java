package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.collaboration.domain.event.TripStartedForList;
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
 * Proves SGART's second process manager (Story 3.1, AD-10): a triggering {@code
 * TripStartedForList} creates the {@code ShoppingTrip} on {@code trip-{id}} with the right list and
 * stores, and re-processing the same event (replay) creates nothing new (idempotent — the derived
 * command id + converge-on-conflict).
 */
class TripStartProcessManagerTest {

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final TripStartProcessManager processManager = new TripStartProcessManager(eventStore);

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final TripId tripId = TripId.generate();
    private final StreamId tripStreamId = StreamId.forTrip(tripId);

    private TripStartedForList triggeringEvent() {
        return new TripStartedForList(
                EventId.generate(), householdId, listId, tripId, List.of(StoreId.generate(), StoreId.generate()));
    }

    @Test
    void createsTheTripOnItsOwnStreamWithTheListAndStores() {
        TripStartedForList started = triggeringEvent();

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
    void replayingTheSameTriggerCreatesNothingNew() {
        TripStartedForList started = triggeringEvent();

        processManager.onTripStartedForList(started);
        processManager.onTripStartedForList(started);

        assertThat(eventStore.readStream(tripStreamId)).hasSize(1);
    }
}
