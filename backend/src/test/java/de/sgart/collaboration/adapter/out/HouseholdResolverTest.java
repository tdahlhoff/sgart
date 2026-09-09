package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.collaboration.domain.readmodel.TripStoreReadModel;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework/persistence/transport (CLAUDE.md §6). Proves the live-sync
 * fan-out's stream-name resolver (Story 4.4, T4/T5): a {@code household-} stream resolves directly
 * from the key with no read-model lookup; {@code list-}/{@code trip-} streams resolve via a
 * read-model lookup that is then cached (a second event on the same stream never re-queries); and
 * the coarse {@code resource} hint derives from the stream key + event-type string alone.
 */
class HouseholdResolverTest {

    @Test
    void resolveHouseholdId_resolvesAHouseholdStreamDirectlyFromTheKey() {
        HouseholdId householdId = HouseholdId.generate();
        HouseholdResolver resolver = new HouseholdResolver(neverCalledListReadModel(), neverCalledTripReadModel());

        Optional<HouseholdId> resolved = resolver.resolveHouseholdId("household-" + householdId.value());

        assertThat(resolved).contains(householdId);
    }

    @Test
    void resolveHouseholdId_resolvesAListStreamViaTheReadModelAndCachesIt() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        AtomicInteger lookups = new AtomicInteger();
        ShoppingListReadModel countingReadModel = new ShoppingListReadModel() {
            @Override
            public List<ShoppingListView> listsOf(HouseholdId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HouseholdId> householdIdOfList(ShoppingListId id) {
                lookups.incrementAndGet();
                return Optional.of(householdId);
            }
        };
        HouseholdResolver resolver = new HouseholdResolver(countingReadModel, neverCalledTripReadModel());

        Optional<HouseholdId> first = resolver.resolveHouseholdId("list-" + listId.value());
        Optional<HouseholdId> second = resolver.resolveHouseholdId("list-" + listId.value());

        assertThat(first).contains(householdId);
        assertThat(second).contains(householdId);
        assertThat(lookups.get()).isEqualTo(1);
    }

    @Test
    void resolveHouseholdId_resolvesATripStreamViaTheReadModelAndCachesIt() {
        HouseholdId householdId = HouseholdId.generate();
        TripId tripId = TripId.generate();
        AtomicInteger lookups = new AtomicInteger();
        TripStoreReadModel countingReadModel = new TripStoreReadModel() {
            @Override
            public void addStore(HouseholdId id, TripId trip, StoreId storeId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<StoreId> storesOf(TripId trip) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteForTrip(TripId trip) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HouseholdId> householdIdOfTrip(TripId trip) {
                lookups.incrementAndGet();
                return Optional.of(householdId);
            }
        };
        HouseholdResolver resolver = new HouseholdResolver(neverCalledListReadModel(), countingReadModel);

        Optional<HouseholdId> first = resolver.resolveHouseholdId("trip-" + tripId.value());
        Optional<HouseholdId> second = resolver.resolveHouseholdId("trip-" + tripId.value());

        assertThat(first).contains(householdId);
        assertThat(second).contains(householdId);
        assertThat(lookups.get()).isEqualTo(1);
    }

    @Test
    void resolveHouseholdId_isEmptyWhenAListRowIsNotYetProjected() {
        HouseholdResolver resolver = new HouseholdResolver(
                new ShoppingListReadModel() {
                    @Override
                    public List<ShoppingListView> listsOf(HouseholdId id) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Optional<HouseholdId> householdIdOfList(ShoppingListId id) {
                        return Optional.empty();
                    }
                },
                neverCalledTripReadModel());

        assertThat(resolver.resolveHouseholdId("list-" + ShoppingListId.generate().value())).isEmpty();
    }

    @Test
    void resourceFor_mapsAListStreamToList() {
        HouseholdResolver resolver = new HouseholdResolver(neverCalledListReadModel(), neverCalledTripReadModel());

        assertThat(resolver.resourceFor("list-" + ShoppingListId.generate().value(), "ItemAdded"))
                .isEqualTo("list");
    }

    @Test
    void resourceFor_mapsATripStreamToTrip() {
        HouseholdResolver resolver = new HouseholdResolver(neverCalledListReadModel(), neverCalledTripReadModel());

        assertThat(resolver.resourceFor("trip-" + TripId.generate().value(), "StoreAddedToTrip"))
                .isEqualTo("trip");
    }

    @Test
    void resourceFor_mapsAMemberOrInviteHouseholdEventToMembers() {
        String streamId = "household-" + HouseholdId.generate().value();
        HouseholdResolver resolver = new HouseholdResolver(neverCalledListReadModel(), neverCalledTripReadModel());

        assertThat(resolver.resourceFor(streamId, "MemberRemoved")).isEqualTo("members");
        assertThat(resolver.resourceFor(streamId, "InviteAccepted")).isEqualTo("members");
    }

    @Test
    void resourceFor_mapsAnyOtherHouseholdEventToHousehold() {
        String streamId = "household-" + HouseholdId.generate().value();
        HouseholdResolver resolver = new HouseholdResolver(neverCalledListReadModel(), neverCalledTripReadModel());

        assertThat(resolver.resourceFor(streamId, "HouseholdRenamed")).isEqualTo("household");
    }

    private static ShoppingListReadModel neverCalledListReadModel() {
        return new ShoppingListReadModel() {
            @Override
            public List<ShoppingListView> listsOf(HouseholdId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HouseholdId> householdIdOfList(ShoppingListId id) {
                throw new AssertionError("household- streams must resolve without a read-model lookup");
            }
        };
    }

    private static TripStoreReadModel neverCalledTripReadModel() {
        return new TripStoreReadModel() {
            @Override
            public void addStore(HouseholdId id, TripId tripId, StoreId storeId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<StoreId> storesOf(TripId tripId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteForTrip(TripId tripId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HouseholdId> householdIdOfTrip(TripId tripId) {
                throw new AssertionError("household- streams must resolve without a read-model lookup");
            }
        };
    }
}
