package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
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
 * zero stores is rejected fail-fast (defence-in-depth, AC3).
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
    void noEventCarriesADisplayNameEmailOrKeycloakUserId() {
        List<String> componentNames = Arrays.stream(TripStarted.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        assertThat(componentNames)
                .noneMatch(name -> name.contains("displayname")
                        || name.contains("email")
                        || name.contains("keycloak"));
    }
}
