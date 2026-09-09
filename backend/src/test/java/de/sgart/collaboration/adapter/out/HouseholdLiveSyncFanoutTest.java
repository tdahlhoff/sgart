package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.LiveConnection;
import de.sgart.collaboration.application.LiveConnectionRegistry;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.HouseholdDeleted;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.event.MemberLeft;
import de.sgart.collaboration.domain.event.MemberRemoved;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.collaboration.domain.readmodel.TripStoreReadModel;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework/transport/event-store connection (CLAUDE.md §6). Proves the
 * live-sync fan-out's routing logic (Story 4.4, T2/T5/T6): every event broadcasts a content-free
 * nudge for its household, and the three de-link events additionally evict the affected
 * connections (AC3, "mapping = access" for the live channel). Builds realistic event bytes through
 * a codec round-trip (same package, so {@code DomainEventJsonCodec}'s package-private access is
 * available here exactly as it is inside the fan-out).
 */
class HouseholdLiveSyncFanoutTest {

    private final DomainEventJsonCodec codec = new DomainEventJsonCodec();
    private final RecordingRegistry registry = new RecordingRegistry();
    // Never connected: react(...) never touches the KurrentDB client (only start() does).
    private final KurrentDBClient neverConnectedClient =
            KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow("esdb://localhost:1?tls=false"));

    @Test
    void react_broadcastsAContentFreeNudgeForAHouseholdStreamEvent() {
        HouseholdId householdId = HouseholdId.generate();
        HouseholdLiveSyncFanout fanout = fanoutWith(neverCalledShoppingListReadModel(), neverCalledTripStoreReadModel());
        HouseholdRenamed renamed = new HouseholdRenamed(EventId.generate(), householdId, new HouseholdName("Neu"));

        fanout.react("household-" + householdId.value(), "HouseholdRenamed", codec.toJsonBytes(renamed));

        assertThat(registry.broadcasts).containsExactly(new Broadcast(householdId, "household"));
        assertThat(registry.evictedMembers).isEmpty();
        assertThat(registry.evictedHouseholds).isEmpty();
    }

    @Test
    void react_evictsTheRemovedMemberAndStillBroadcasts() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId removedMemberId = MemberId.generate();
        HouseholdLiveSyncFanout fanout = fanoutWith(neverCalledShoppingListReadModel(), neverCalledTripStoreReadModel());
        MemberRemoved removed =
                new MemberRemoved(EventId.generate(), householdId, removedMemberId, MemberId.generate());

        fanout.react("household-" + householdId.value(), "MemberRemoved", codec.toJsonBytes(removed));

        assertThat(registry.evictedMembers).containsExactly(new EvictedMember(householdId, removedMemberId));
        assertThat(registry.broadcasts).containsExactly(new Broadcast(householdId, "members"));
    }

    @Test
    void react_evictsTheLeavingMemberAndStillBroadcasts() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId leavingMemberId = MemberId.generate();
        HouseholdLiveSyncFanout fanout = fanoutWith(neverCalledShoppingListReadModel(), neverCalledTripStoreReadModel());
        MemberLeft left = new MemberLeft(EventId.generate(), householdId, leavingMemberId);

        fanout.react("household-" + householdId.value(), "MemberLeft", codec.toJsonBytes(left));

        assertThat(registry.evictedMembers).containsExactly(new EvictedMember(householdId, leavingMemberId));
        assertThat(registry.broadcasts).containsExactly(new Broadcast(householdId, "members"));
    }

    @Test
    void react_evictsTheWholeHouseholdOnHouseholdDeletedAndStillBroadcasts() {
        HouseholdId householdId = HouseholdId.generate();
        HouseholdLiveSyncFanout fanout = fanoutWith(neverCalledShoppingListReadModel(), neverCalledTripStoreReadModel());
        HouseholdDeleted deleted = new HouseholdDeleted(EventId.generate(), householdId, MemberId.generate());

        fanout.react("household-" + householdId.value(), "HouseholdDeleted", codec.toJsonBytes(deleted));

        assertThat(registry.evictedHouseholds).containsExactly(householdId);
        assertThat(registry.broadcasts).containsExactly(new Broadcast(householdId, "household"));
    }

    @Test
    void start_schedulesAResubscribeWhenTheInitialSubscribeToAllFailsOutright() throws InterruptedException {
        // neverConnectedClient points at a refused local port (esdb://localhost:1) — subscribeToAll
        // eventually completes exceptionally once the underlying gRPC channel gives up retrying.
        HouseholdLiveSyncFanout fanout = fanoutWith(neverCalledShoppingListReadModel(), neverCalledTripStoreReadModel());
        try {
            fanout.start();

            for (int attempt = 0; attempt < 100; attempt++) {
                if (fanout.resubscribeScheduleCount() > 0) {
                    return;
                }
                Thread.sleep(200);
            }
            throw new AssertionError(
                    "expected a resubscribe to be scheduled after the initial subscribeToAll failed outright");
        } finally {
            fanout.stop();
        }
    }

    @Test
    void react_skipsSilentlyWhenTheHouseholdCannotYetBeResolved() {
        ShoppingListReadModel unresolvedReadModel = new ShoppingListReadModel() {
            @Override
            public List<ShoppingListView> listsOf(HouseholdId householdId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HouseholdId> householdIdOfList(ShoppingListId listId) {
                return Optional.empty();
            }
        };
        HouseholdLiveSyncFanout fanout = fanoutWith(unresolvedReadModel, neverCalledTripStoreReadModel());

        fanout.react("list-" + ShoppingListId.generate().value(), "ItemRemoved", new byte[0]);

        assertThat(registry.broadcasts).isEmpty();
        assertThat(registry.evictedMembers).isEmpty();
        assertThat(registry.evictedHouseholds).isEmpty();
    }

    private HouseholdLiveSyncFanout fanoutWith(ShoppingListReadModel listReadModel, TripStoreReadModel tripReadModel) {
        return new HouseholdLiveSyncFanout(neverConnectedClient, registry, new HouseholdResolver(listReadModel, tripReadModel));
    }

    private static ShoppingListReadModel neverCalledShoppingListReadModel() {
        return new ShoppingListReadModel() {
            @Override
            public List<ShoppingListView> listsOf(HouseholdId householdId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static TripStoreReadModel neverCalledTripStoreReadModel() {
        return new TripStoreReadModel() {
            @Override
            public void addStore(HouseholdId householdId, TripId tripId, StoreId storeId) {
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
        };
    }

    private record Broadcast(HouseholdId householdId, String resource) {}

    private record EvictedMember(HouseholdId householdId, MemberId memberId) {}

    /** A hand-rolled recording fake (this codebase's test-double style — no mocking library). */
    private static final class RecordingRegistry implements LiveConnectionRegistry {
        final List<Broadcast> broadcasts = new ArrayList<>();
        final List<EvictedMember> evictedMembers = new ArrayList<>();
        final List<HouseholdId> evictedHouseholds = new ArrayList<>();

        @Override
        public void register(HouseholdId householdId, MemberId memberId, LiveConnection connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deregister(HouseholdId householdId, MemberId memberId, LiveConnection connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void evictMember(HouseholdId householdId, MemberId memberId) {
            evictedMembers.add(new EvictedMember(householdId, memberId));
        }

        @Override
        public void evictHousehold(HouseholdId householdId) {
            evictedHouseholds.add(householdId);
        }

        @Override
        public void broadcast(HouseholdId householdId, String resource) {
            broadcasts.add(new Broadcast(householdId, resource));
        }
    }
}
