package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.ChangeNudge;
import de.sgart.collaboration.application.ContentFreePushSender;
import de.sgart.collaboration.application.PushDeliveryResult;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.collaboration.domain.readmodel.TripStoreReadModel;
import de.sgart.identity.adapter.out.InMemoryDeviceTokenRepository;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.PruneDeviceToken;
import de.sgart.identity.application.PushTarget;
import de.sgart.identity.application.ResolveHouseholdPushTargets;
import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.DeviceToken;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import io.kurrent.dbclient.Subscription;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework/transport/event-store connection (CLAUDE.md §6). Proves the
 * notification fan-out's routing (Story 4.5, AC2), debounce boundary (AC2), recipient resolution
 * ("mapping = access", AC4), and stale-token pruning (AC5). Mirrors {@link
 * HouseholdLiveSyncFanoutTest}'s style exactly, composing the real {@link ResolveHouseholdPushTargets}
 * / {@link PruneDeviceToken} application services over in-memory Identity repositories (they carry
 * no I/O, so this stays a fast unit test, not an integration test).
 */
class HouseholdNotificationFanoutTest {

    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");
    private static final Duration DEBOUNCE_WINDOW = Duration.ofMinutes(5);

    private final InMemoryMemberMappingRepository memberMappingRepository = new InMemoryMemberMappingRepository();
    private final InMemoryDeviceTokenRepository deviceTokenRepository = new InMemoryDeviceTokenRepository();
    private final ResolveHouseholdPushTargets resolveHouseholdPushTargets =
            new ResolveHouseholdPushTargets(memberMappingRepository, deviceTokenRepository);
    private final PruneDeviceToken pruneDeviceToken = new PruneDeviceToken(deviceTokenRepository);
    private final RecordingPushSender pushSender = new RecordingPushSender();
    private final MutableClock clock = new MutableClock(NOW);
    // Never connected: react(...) never touches the KurrentDB client (only start() does).
    private final KurrentDBClient neverConnectedClient =
            KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow("esdb://localhost:1?tls=false"));

    private HouseholdId registerOneMemberWithOneDevice(HouseholdId householdId) {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        memberMappingRepository.save(new MemberMapping(householdId, MemberId.generate(), keycloakUserId));
        deviceTokenRepository.upsert(new DeviceToken(keycloakUserId, "anna-phone", DevicePlatform.ANDROID, NOW));
        return householdId;
    }

    @Test
    void react_pushesAListChangedNudgeToEveryRegisteredDevice() {
        HouseholdId householdId = registerOneMemberWithOneDevice(HouseholdId.generate());
        ShoppingListId listId = ShoppingListId.generate();
        HouseholdNotificationFanout fanout = fanoutWith(fixedListReadModel(listId, householdId), neverCalledTripReadModel());

        fanout.react("list-" + listId.value(), "ItemAdded", new byte[0]);

        assertThat(pushSender.sent).containsExactly(new SentPush(new PushTarget("anna-phone", "ANDROID"),
                new ChangeNudge(householdId, "list")));
    }

    @Test
    void react_debouncesASecondListChangedEventForTheSameListWithinTheWindow() {
        HouseholdId householdId = registerOneMemberWithOneDevice(HouseholdId.generate());
        ShoppingListId listId = ShoppingListId.generate();
        HouseholdNotificationFanout fanout = fanoutWith(fixedListReadModel(listId, householdId), neverCalledTripReadModel());

        fanout.react("list-" + listId.value(), "ItemAdded", new byte[0]);
        clock.advance(DEBOUNCE_WINDOW.minusSeconds(1));
        fanout.react("list-" + listId.value(), "ItemUpdated", new byte[0]);

        assertThat(pushSender.sent).hasSize(1);
    }

    @Test
    void react_allowsAPingExactlyAtTheDebounceWindowBoundary() {
        HouseholdId householdId = registerOneMemberWithOneDevice(HouseholdId.generate());
        ShoppingListId listId = ShoppingListId.generate();
        HouseholdNotificationFanout fanout = fanoutWith(fixedListReadModel(listId, householdId), neverCalledTripReadModel());

        fanout.react("list-" + listId.value(), "ItemAdded", new byte[0]);
        clock.advance(DEBOUNCE_WINDOW); // exactly at the boundary — inclusive
        fanout.react("list-" + listId.value(), "ItemUpdated", new byte[0]);

        assertThat(pushSender.sent).hasSize(2);
    }

    @Test
    void react_doesNotDebounceAcrossDifferentLists() {
        HouseholdId householdId = registerOneMemberWithOneDevice(HouseholdId.generate());
        ShoppingListId firstListId = ShoppingListId.generate();
        ShoppingListId secondListId = ShoppingListId.generate();
        ShoppingListReadModel readModel = new ShoppingListReadModel() {
            @Override
            public List<ShoppingListView> listsOf(HouseholdId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HouseholdId> householdIdOfList(ShoppingListId listId) {
                return Optional.of(householdId);
            }
        };
        HouseholdNotificationFanout fanout = fanoutWith(readModel, neverCalledTripReadModel());

        fanout.react("list-" + firstListId.value(), "ItemAdded", new byte[0]);
        fanout.react("list-" + secondListId.value(), "ItemAdded", new byte[0]);

        assertThat(pushSender.sent).hasSize(2);
    }

    @Test
    void react_pushesATripStartedNudgeNeverDebounced() {
        HouseholdId householdId = registerOneMemberWithOneDevice(HouseholdId.generate());
        TripId tripId = TripId.generate();
        HouseholdNotificationFanout fanout = fanoutWith(neverCalledListReadModel(), fixedTripReadModel(tripId, householdId));

        fanout.react("trip-" + tripId.value(), "TripStarted", new byte[0]);
        fanout.react("trip-" + tripId.value(), "TripStarted", new byte[0]); // same instant — not debounced

        assertThat(pushSender.sent).containsExactly(
                new SentPush(new PushTarget("anna-phone", "ANDROID"), new ChangeNudge(householdId, "trip")),
                new SentPush(new PushTarget("anna-phone", "ANDROID"), new ChangeNudge(householdId, "trip")));
    }

    @Test
    void react_pushesATripCompletedNudge() {
        HouseholdId householdId = registerOneMemberWithOneDevice(HouseholdId.generate());
        TripId tripId = TripId.generate();
        HouseholdNotificationFanout fanout = fanoutWith(neverCalledListReadModel(), fixedTripReadModel(tripId, householdId));

        fanout.react("trip-" + tripId.value(), "TripCompleted", new byte[0]);

        assertThat(pushSender.sent).containsExactly(
                new SentPush(new PushTarget("anna-phone", "ANDROID"), new ChangeNudge(householdId, "trip")));
    }

    /** TripStartedForList/TripCompletedForList are list-scoped siblings of TripStarted/TripCompleted
     * (Story 3.1/3.4) — ignored here so the same real-world action never fires two pings (AC2). */
    @Test
    void react_ignoresTripStartedForListAndTripCompletedForListOnTheListStream() {
        HouseholdId householdId = registerOneMemberWithOneDevice(HouseholdId.generate());
        ShoppingListId listId = ShoppingListId.generate();
        HouseholdNotificationFanout fanout = fanoutWith(fixedListReadModel(listId, householdId), neverCalledTripReadModel());

        fanout.react("list-" + listId.value(), "TripStartedForList", new byte[0]);
        fanout.react("list-" + listId.value(), "TripCompletedForList", new byte[0]);

        assertThat(pushSender.sent).isEmpty();
    }

    /** MVP fixed trigger set (AC2, D1): household-/member-scoped events never push. */
    @Test
    void react_ignoresHouseholdScopedEvents() {
        HouseholdId householdId = registerOneMemberWithOneDevice(HouseholdId.generate());
        HouseholdNotificationFanout fanout = fanoutWith(neverCalledListReadModel(), neverCalledTripReadModel());

        fanout.react("household-" + householdId.value(), "HouseholdRenamed", new byte[0]);
        fanout.react("household-" + householdId.value(), "MemberJoined", new byte[0]);

        assertThat(pushSender.sent).isEmpty();
    }

    @Test
    void react_skipsSilentlyWhenTheHouseholdCannotYetBeResolved() {
        ShoppingListReadModel unresolvedReadModel = new ShoppingListReadModel() {
            @Override
            public List<ShoppingListView> listsOf(HouseholdId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HouseholdId> householdIdOfList(ShoppingListId listId) {
                return Optional.empty();
            }
        };
        HouseholdNotificationFanout fanout = fanoutWith(unresolvedReadModel, neverCalledTripReadModel());

        fanout.react("list-" + ShoppingListId.generate().value(), "ItemAdded", new byte[0]);

        assertThat(pushSender.sent).isEmpty();
    }

    /** "mapping = access" (AC4): a de-linked member (no mapping row) yields no push even with a registered device. */
    @Test
    void react_yieldsNoPushForADeLinkedMember() {
        HouseholdId householdId = HouseholdId.generate();
        deviceTokenRepository.upsert(
                new DeviceToken(new KeycloakUserId("former-member-sub"), "former-phone", DevicePlatform.ANDROID, NOW));
        ShoppingListId listId = ShoppingListId.generate();
        HouseholdNotificationFanout fanout = fanoutWith(fixedListReadModel(listId, householdId), neverCalledTripReadModel());

        fanout.react("list-" + listId.value(), "ItemAdded", new byte[0]);

        assertThat(pushSender.sent).isEmpty();
    }

    @Test
    void react_prunesTheTokenWhenTheTransportReportsItInvalid() {
        HouseholdId householdId = registerOneMemberWithOneDevice(HouseholdId.generate());
        pushSender.tokensToRejectAsInvalid.add("anna-phone");
        ShoppingListId listId = ShoppingListId.generate();
        HouseholdNotificationFanout fanout = fanoutWith(fixedListReadModel(listId, householdId), neverCalledTripReadModel());

        fanout.react("list-" + listId.value(), "ItemAdded", new byte[0]);

        assertThat(deviceTokenRepository.findByToken("anna-phone")).isEmpty();
    }

    @Test
    void retainSubscription_rejectsASubscriptionThatLandsAfterStop() {
        // The shutdown race (Epic 4 retro): subscribeToAll returns on the resubscribe thread AFTER
        // stop() already ran. The guard must refuse to retain it, so the caller cancels it rather
        // than orphaning a live $all subscription that stop() can no longer see.
        HouseholdNotificationFanout fanout = fanoutWith(neverCalledListReadModel(), neverCalledTripReadModel());
        fanout.start();
        fanout.stop();

        CompletableFuture<Subscription> lateSubscription = new CompletableFuture<>();

        assertThat(fanout.retainSubscription(lateSubscription)).isFalse();
    }

    @Test
    void retainSubscription_retainsASubscriptionWhileRunning() {
        HouseholdNotificationFanout fanout = fanoutWith(neverCalledListReadModel(), neverCalledTripReadModel());
        fanout.start();
        try {
            CompletableFuture<Subscription> subscription = new CompletableFuture<>();

            assertThat(fanout.retainSubscription(subscription)).isTrue();
        } finally {
            fanout.stop();
        }
    }

    private HouseholdNotificationFanout fanoutWith(ShoppingListReadModel listReadModel, TripStoreReadModel tripReadModel) {
        return new HouseholdNotificationFanout(
                neverConnectedClient,
                pushSender,
                resolveHouseholdPushTargets,
                pruneDeviceToken,
                new HouseholdResolver(listReadModel, tripReadModel),
                clock,
                DEBOUNCE_WINDOW);
    }

    private static ShoppingListReadModel fixedListReadModel(ShoppingListId listId, HouseholdId householdId) {
        return new ShoppingListReadModel() {
            @Override
            public List<ShoppingListView> listsOf(HouseholdId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HouseholdId> householdIdOfList(ShoppingListId requestedListId) {
                return requestedListId.equals(listId) ? Optional.of(householdId) : Optional.empty();
            }
        };
    }

    private static TripStoreReadModel fixedTripReadModel(TripId tripId, HouseholdId householdId) {
        return new TripStoreReadModel() {
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
            public Optional<HouseholdId> householdIdOfTrip(TripId requestedTripId) {
                return requestedTripId.equals(tripId) ? Optional.of(householdId) : Optional.empty();
            }
        };
    }

    private static ShoppingListReadModel neverCalledListReadModel() {
        return new ShoppingListReadModel() {
            @Override
            public List<ShoppingListView> listsOf(HouseholdId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HouseholdId> householdIdOfList(ShoppingListId listId) {
                throw new AssertionError("must not resolve a list for this stream");
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
                throw new AssertionError("must not resolve a trip for this stream");
            }
        };
    }

    private record SentPush(PushTarget target, ChangeNudge nudge) {}

    /** A hand-rolled recording fake (this codebase's test-double style — no mocking library). */
    private static final class RecordingPushSender implements ContentFreePushSender {
        final List<SentPush> sent = new ArrayList<>();
        final Set<String> tokensToRejectAsInvalid = new HashSet<>();

        @Override
        public PushDeliveryResult send(PushTarget target, ChangeNudge nudge) {
            sent.add(new SentPush(target, nudge));
            return tokensToRejectAsInvalid.contains(target.token())
                    ? PushDeliveryResult.TOKEN_INVALID
                    : PushDeliveryResult.DELIVERED;
        }
    }

    /** A settable {@link Clock} test double (CLAUDE.md §6 — isolate the clock for deterministic debounce tests). */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
