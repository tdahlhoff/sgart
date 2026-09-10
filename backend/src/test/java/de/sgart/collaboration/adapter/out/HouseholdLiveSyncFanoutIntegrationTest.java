package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.LiveConnection;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.HouseholdDeleted;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.MemberRemoved;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.MemberId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers integration test proving the live-sync fan-out end-to-end through the <em>real
 * live {@code fromEnd} KurrentDB subscription</em> (Story 4.4, T12, AC1/AC3) — the one path {@link
 * HouseholdLiveSyncFanoutTest}'s direct {@code react(...)} calls cannot cover: append via the real
 * {@link KurrentDbEventStore} → the fan-out's live subscription observes it → the registry is
 * notified. Uses real KurrentDB {@code 25.1.4} and real PostgreSQL {@code 18.6} (the list-scoped
 * resolver lookup), owning both container lifecycles; never points at the dev compose services.
 * Mirrors {@code HouseholdReadModelSubscriptionTest}'s polling-loop style (no Awaitility dependency
 * in this project).
 */
@Testcontainers
class HouseholdLiveSyncFanoutIntegrationTest {

    @Container
    static final GenericContainer<?> KURRENTDB =
            new GenericContainer<>("docker.kurrent.io/kurrent-latest/kurrentdb:25.1.4")
                    .withExposedPorts(2113)
                    .withEnv("KURRENTDB_CLUSTER_SIZE", "1")
                    .withEnv("KURRENTDB_RUN_PROJECTIONS", "All")
                    .withEnv("KURRENTDB_START_STANDARD_PROJECTIONS", "true")
                    .withEnv("KURRENTDB_INSECURE", "true")
                    .withEnv("KURRENTDB_ENABLE_ATOM_PUB_OVER_HTTP", "true")
                    .waitingFor(Wait.forHttp("/health/live")
                            .forPort(2113)
                            .forStatusCode(204)
                            .withStartupTimeout(Duration.ofMinutes(2)));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static KurrentDBClient client;
    private static DataSource dataSource;

    private KurrentDbEventStore eventStore;
    private HouseholdLiveSyncFanout fanout;
    private RecordingRegistry registry;
    private HouseholdResolver resolver;

    @BeforeAll
    static void startInfrastructure() {
        String connectionString =
                "esdb://" + KURRENTDB.getHost() + ":" + KURRENTDB.getMappedPort(2113) + "?tls=false";
        client = KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow(connectionString));

        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl());
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void closeClient() {
        client.shutdown().join();
    }

    @BeforeEach
    void setUp() {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("TRUNCATE TABLE shopping_list_read_model, item_read_model").update();
        eventStore = new KurrentDbEventStore(client);
        registry = new RecordingRegistry();
        resolver =
                new HouseholdResolver(new JdbcShoppingListReadModel(jdbcClient), new JdbcTripStoreReadModel(jdbcClient));
        fanout = new HouseholdLiveSyncFanout(client, registry, resolver);
        fanout.start();
    }

    @AfterEach
    void tearDown() {
        fanout.stop();
    }

    @Test
    void aHouseholdEventBroadcastsOnlyToItsOwnHousehold() throws InterruptedException {
        HouseholdId householdId = HouseholdId.generate();
        HouseholdId otherHouseholdId = HouseholdId.generate();

        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                List.of(new de.sgart.collaboration.domain.event.HouseholdRenamed(
                        EventId.generate(), householdId, new HouseholdName("Neu"))),
                CommandId.generate());

        awaitBroadcast(householdId, "household");
        assertThat(registry.broadcastsFor(otherHouseholdId)).isEmpty();
    }

    @Test
    void aListScopedEventWithNoHouseholdIdFieldResolvesViaTheReadModelAndBroadcastsList() throws InterruptedException {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        // Seed the resolver's read-model lookup row directly — in production the
        // ShoppingListReadModelProjector's own (separately-tested) subscription would have already
        // projected ShoppingListCreated by the time a later list event fires (Dev Notes: "the list
        // exists when any list event fires, so there is no projector race in practice").
        new JdbcShoppingListReadModel(JdbcClient.create(dataSource)).insertList(householdId, listId, null);

        eventStore.append(
                AggregateVersion.initial(StreamId.forList(listId)),
                List.of(new ItemRemoved(EventId.generate(), listId, ItemId.generate())),
                CommandId.generate());

        awaitBroadcast(householdId, "list");
    }

    @Test
    void memberRemovedEvictsThatMembersConnectionAndStillBroadcastsMembers() throws InterruptedException {
        HouseholdId householdId = HouseholdId.generate();
        MemberId removedMemberId = MemberId.generate();
        RecordingConnection connection = new RecordingConnection();
        registry.register(householdId, removedMemberId, connection);

        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                List.of(new MemberRemoved(EventId.generate(), householdId, removedMemberId, MemberId.generate())),
                CommandId.generate());

        awaitTrue(() -> connection.closed, "the removed member's connection was never evicted");
        awaitBroadcast(householdId, "members");
    }

    @Test
    void householdDeletedEvictsEveryConnectionForThatHousehold() throws InterruptedException {
        HouseholdId householdId = HouseholdId.generate();
        RecordingConnection connectionOne = new RecordingConnection();
        RecordingConnection connectionTwo = new RecordingConnection();
        registry.register(householdId, MemberId.generate(), connectionOne);
        registry.register(householdId, MemberId.generate(), connectionTwo);

        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                List.of(new HouseholdDeleted(EventId.generate(), householdId, MemberId.generate())),
                CommandId.generate());

        awaitTrue(() -> connectionOne.closed && connectionTwo.closed, "not every connection was evicted");
    }

    @Test
    void stop_cancelsTheLiveSubscriptionSoNoFurtherEventsAreBroadcast() throws InterruptedException {
        HouseholdId householdId = HouseholdId.generate();
        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                List.of(new de.sgart.collaboration.domain.event.HouseholdRenamed(
                        EventId.generate(), householdId, new HouseholdName("Vor dem Stop"))),
                CommandId.generate());
        awaitBroadcast(householdId, "household");

        fanout.stop();

        HouseholdId afterStopHouseholdId = HouseholdId.generate();
        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(afterStopHouseholdId)),
                List.of(new de.sgart.collaboration.domain.event.HouseholdRenamed(
                        EventId.generate(), afterStopHouseholdId, new HouseholdName("Nach dem Stop"))),
                CommandId.generate());

        // A negative wait: without the fix, the un-cancelled $all subscription keeps firing after
        // stop() and this would eventually observe the broadcast anyway.
        Thread.sleep(2000);
        assertThat(registry.broadcastsFor(afterStopHouseholdId)).isEmpty();
    }

    @Test
    void fromEnd_neverReplaysEventsThatPrecededTheSubscription() throws InterruptedException {
        // LD-2: the fan-out subscribes fromEnd and holds no per-client position — a downtime gap is
        // healed by the client's reconnect-refetch, NEVER by replay. Stop setUp's live subscription
        // so nothing is observing while we write the "before" event.
        fanout.stop();

        HouseholdId beforeSubscribeHouseholdId = HouseholdId.generate();
        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(beforeSubscribeHouseholdId)),
                List.of(new de.sgart.collaboration.domain.event.HouseholdRenamed(
                        EventId.generate(), beforeSubscribeHouseholdId, new HouseholdName("Vor dem Abo"))),
                CommandId.generate());

        // A fresh fan-out that subscribes fromEnd *after* the event above already exists in $all.
        RecordingRegistry laterRegistry = new RecordingRegistry();
        HouseholdLiveSyncFanout laterFanout = new HouseholdLiveSyncFanout(client, laterRegistry, resolver);
        laterFanout.start();
        try {
            // Let the fromEnd subscription establish before writing the "after" event, so it lands
            // strictly after the subscription's end position (mirrors the negative-wait style used
            // by stop_cancelsTheLiveSubscription...).
            Thread.sleep(1500);

            HouseholdId afterSubscribeHouseholdId = HouseholdId.generate();
            eventStore.append(
                    AggregateVersion.initial(StreamId.forHousehold(afterSubscribeHouseholdId)),
                    List.of(new de.sgart.collaboration.domain.event.HouseholdRenamed(
                            EventId.generate(), afterSubscribeHouseholdId, new HouseholdName("Nach dem Abo"))),
                    CommandId.generate());

            // The post-subscription event is delivered...
            awaitTrue(
                    () -> laterRegistry.broadcastsFor(afterSubscribeHouseholdId).contains("household"),
                    "the fromEnd subscription did not observe the event appended after it started");
            // ...but the pre-subscription event is never replayed.
            assertThat(laterRegistry.broadcastsFor(beforeSubscribeHouseholdId)).isEmpty();
        } finally {
            laterFanout.stop();
        }
    }

    private void awaitBroadcast(HouseholdId householdId, String resource) throws InterruptedException {
        awaitTrue(
                () -> registry.broadcastsFor(householdId).contains(resource),
                "the live subscription did not broadcast '" + resource + "' for " + householdId + " within the timeout");
    }

    /** Polls the eventually-consistent async subscription until it observes the expected effect. */
    private void awaitTrue(java.util.function.BooleanSupplier condition, String failureMessage)
            throws InterruptedException {
        for (int attempt = 0; attempt < 80; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError(failureMessage);
    }

    private static final class RecordingConnection implements LiveConnection {
        volatile boolean closed;

        @Override
        public void sendChangedEvent(String resource) {}

        @Override
        public void sendHeartbeat() {}

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A hand-rolled recording fake registry, real broadcast/evict semantics via delegation. */
    private static final class RecordingRegistry implements de.sgart.collaboration.application.LiveConnectionRegistry {
        private final HouseholdEmitterRegistry delegate = new HouseholdEmitterRegistry();
        private final ConcurrentLinkedQueue<Broadcast> broadcasts = new ConcurrentLinkedQueue<>();

        List<String> broadcastsFor(HouseholdId householdId) {
            return broadcasts.stream()
                    .filter(broadcast -> broadcast.householdId.equals(householdId))
                    .map(broadcast -> broadcast.resource)
                    .toList();
        }

        @Override
        public void register(HouseholdId householdId, MemberId memberId, LiveConnection connection) {
            delegate.register(householdId, memberId, connection);
        }

        @Override
        public void deregister(HouseholdId householdId, MemberId memberId, LiveConnection connection) {
            delegate.deregister(householdId, memberId, connection);
        }

        @Override
        public void evictMember(HouseholdId householdId, MemberId memberId) {
            delegate.evictMember(householdId, memberId);
        }

        @Override
        public void evictHousehold(HouseholdId householdId) {
            delegate.evictHousehold(householdId);
        }

        @Override
        public void broadcast(HouseholdId householdId, String resource) {
            broadcasts.add(new Broadcast(householdId, resource));
            delegate.broadcast(householdId, resource);
        }

        private record Broadcast(HouseholdId householdId, String resource) {}
    }
}
