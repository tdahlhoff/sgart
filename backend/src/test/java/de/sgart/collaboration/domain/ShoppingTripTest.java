package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.event.StoreAddedToTrip;
import de.sgart.collaboration.domain.event.TripCompleted;
import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.collaboration.domain.exception.TripNotActiveException;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves
 * SGART's third aggregate (Story 3.1): {@code start} raises {@code TripStarted} carrying the
 * linked list and stores at version one, status {@code ACTIVE}; replay rebuilds identical state;
 * zero stores is rejected fail-fast (defence-in-depth, AC3). Also proves the Story 3.2 {@code
 * addStore} transition (AC3): a store added to an Active trip raises {@code StoreAddedToTrip} and
 * folds in add order; an already-present store is a convergent no-op.
 */
class ShoppingTripTest {

    private final TripId tripId = TripId.generate();
    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final CommandId commandId = CommandId.generate();

    @Test
    void start_raisesTripStarted_withListAndStores() {
        StoreId storeId = StoreId.generate();

        ShoppingTrip trip = ShoppingTrip.start(tripId, householdId, listId, List.of(storeId), commandId);

        List<DomainEvent> events = trip.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(TripStarted.class);
        TripStarted started = (TripStarted) events.get(0);
        assertThat(started.tripId()).isEqualTo(tripId);
        assertThat(started.householdId()).isEqualTo(householdId);
        assertThat(started.listId()).isEqualTo(listId);
        assertThat(started.storeIds()).containsExactly(storeId);

        assertThat(trip.tripId()).isEqualTo(tripId);
        assertThat(trip.householdId()).isEqualTo(householdId);
        assertThat(trip.listId()).isEqualTo(listId);
        assertThat(trip.storeIds()).containsExactly(storeId);
        assertThat(trip.status()).isEqualTo(TripStatus.ACTIVE);
        assertThat(trip.version()).isEqualTo(AggregateVersion.of(StreamId.forTrip(tripId), 1));
    }

    @Test
    void start_withMultipleStores_keepsThemAll() {
        StoreId edeka = StoreId.generate();
        StoreId netto = StoreId.generate();

        ShoppingTrip trip = ShoppingTrip.start(tripId, householdId, listId, List.of(edeka, netto), commandId);

        assertThat(trip.storeIds()).containsExactly(edeka, netto);
    }

    @Test
    void start_withDuplicateStores_dedupesThem() {
        StoreId edeka = StoreId.generate();

        ShoppingTrip trip =
                ShoppingTrip.start(tripId, householdId, listId, List.of(edeka, edeka), commandId);

        assertThat(trip.storeIds()).containsExactly(edeka);
    }

    @Test
    void start_withNoStores_throws() {
        assertThatThrownBy(() -> ShoppingTrip.start(tripId, householdId, listId, List.of(), commandId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void start_rejectsANullTripId() {
        assertThatThrownBy(() ->
                        ShoppingTrip.start(null, householdId, listId, List.of(StoreId.generate()), commandId))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rehydrate_TripStarted_isActiveWithItsStores() {
        StoreId storeId = StoreId.generate();
        ShoppingTrip original = ShoppingTrip.start(tripId, householdId, listId, List.of(storeId), commandId);
        List<DomainEvent> history = original.uncommittedEvents();

        ShoppingTrip rehydrated = ShoppingTrip.rehydrate(StreamId.forTrip(tripId), history);

        assertThat(rehydrated.tripId()).isEqualTo(original.tripId());
        assertThat(rehydrated.householdId()).isEqualTo(original.householdId());
        assertThat(rehydrated.listId()).isEqualTo(original.listId());
        assertThat(rehydrated.storeIds()).isEqualTo(original.storeIds());
        assertThat(rehydrated.status()).isEqualTo(TripStatus.ACTIVE);
        assertThat(rehydrated.version()).isEqualTo(original.version());
    }

    @Test
    void storeIds_returnsAnUnmodifiableCopy() {
        ShoppingTrip trip =
                ShoppingTrip.start(tripId, householdId, listId, List.of(StoreId.generate()), commandId);

        assertThatThrownBy(() -> trip.storeIds().add(StoreId.generate()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void addStore_onActiveTrip_raisesStoreAddedToTrip_andFolds() {
        StoreId edeka = StoreId.generate();
        ShoppingTrip trip = ShoppingTrip.start(tripId, householdId, listId, List.of(edeka), commandId);
        trip.markEventsCommitted();
        StoreId netto = StoreId.generate();

        trip.addStore(netto, CommandId.generate());

        List<DomainEvent> events = trip.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StoreAddedToTrip.class);
        StoreAddedToTrip added = (StoreAddedToTrip) events.get(0);
        assertThat(added.tripId()).isEqualTo(tripId);
        assertThat(added.householdId()).isEqualTo(householdId);
        assertThat(added.storeId()).isEqualTo(netto);
        assertThat(trip.storeIds()).containsExactly(edeka, netto);
    }

    @Test
    void addStore_forAStoreAlreadyInTheTrip_isAConvergentNoOp() {
        StoreId edeka = StoreId.generate();
        ShoppingTrip trip = ShoppingTrip.start(tripId, householdId, listId, List.of(edeka), commandId);
        trip.markEventsCommitted();

        trip.addStore(edeka, CommandId.generate());

        assertThat(trip.uncommittedEvents()).isEmpty();
    }

    @Test
    void addStore_foldsInAddOrder() {
        StoreId edeka = StoreId.generate();
        StoreId netto = StoreId.generate();
        ShoppingTrip trip = ShoppingTrip.rehydrate(
                StreamId.forTrip(tripId),
                List.of(
                        new TripStarted(EventId.generate(), tripId, householdId, listId, List.of(edeka)),
                        new StoreAddedToTrip(EventId.generate(), tripId, householdId, netto)));

        assertThat(trip.storeIds()).containsExactly(edeka, netto);
    }

    @Test
    void addStore_onADoneTrip_throwsTripNotActive() {
        ShoppingTrip trip = ShoppingTrip.start(tripId, householdId, listId, List.of(StoreId.generate()), commandId);
        trip.markEventsCommitted();
        setStatus(trip, TripStatus.DONE);

        assertThatThrownBy(() -> trip.addStore(StoreId.generate(), CommandId.generate()))
                .isInstanceOf(TripNotActiveException.class);
    }

    // ── complete ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void complete_onAnActiveTrip_raisesTripCompleted_andFoldsToDone() {
        ShoppingTrip trip = ShoppingTrip.start(tripId, householdId, listId, List.of(StoreId.generate()), commandId);
        trip.markEventsCommitted();

        trip.complete(CommandId.generate());

        List<DomainEvent> events = trip.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(TripCompleted.class);
        TripCompleted completed = (TripCompleted) events.get(0);
        assertThat(completed.tripId()).isEqualTo(tripId);
        assertThat(completed.householdId()).isEqualTo(householdId);
        assertThat(completed.listId()).isEqualTo(listId);
        assertThat(trip.status()).isEqualTo(TripStatus.DONE);
    }

    @Test
    void complete_onAnAlreadyDoneTrip_isAConvergentNoOp() {
        ShoppingTrip trip = ShoppingTrip.rehydrate(
                StreamId.forTrip(tripId),
                List.of(
                        new TripStarted(EventId.generate(), tripId, householdId, listId, List.of(StoreId.generate())),
                        new TripCompleted(EventId.generate(), tripId, householdId, listId)));

        trip.complete(CommandId.generate());

        assertThat(trip.uncommittedEvents()).isEmpty();
        assertThat(trip.status()).isEqualTo(TripStatus.DONE);
    }

    private void setStatus(ShoppingTrip trip, TripStatus status) {
        try {
            java.lang.reflect.Field field = ShoppingTrip.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(trip, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void noEventCarriesADisplayNameEmailOrKeycloakUserId() {
        assertNoPersonalDataComponent(TripStarted.class);
        assertNoPersonalDataComponent(StoreAddedToTrip.class);
    }

    private void assertNoPersonalDataComponent(Class<? extends DomainEvent> eventType) {
        List<String> componentNames = Arrays.stream(eventType.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        assertThat(componentNames)
                .noneMatch(name -> name.contains("displayname")
                        || name.contains("email")
                        || name.contains("keycloak"));
    }
}
