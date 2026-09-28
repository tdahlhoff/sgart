package de.sgart.collaboration.adapter.out;

import static de.sgart.collaboration.adapter.out.LiveSubscriptionAwait.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.HouseholdDeleted;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import java.time.Duration;
import java.util.List;
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
 * Regression guard for LD-2, surfaced 2026-09-12 running the real stack for the first time
 * (docs/first-real-world-test.md): {@link ShoppingListReadModelProjector} and {@link
 * ShoppingTripReadModelProjector} each built their subscription filter with two chained {@code
 * addStreamNamePrefix} calls, which the kurrentdb-client (1.2.1) {@code SubscriptionFilterBuilder}
 * rejects on the second call with {@code IllegalStateException("Filter type is already set to
 * STREAM")} — a defect neither class's own {@code *Test.java} caught, because those drive {@code
 * project(...)} directly against a Postgres Testcontainer and never open the real subscription.
 * Both now build the filter with a single regular expression instead (matching {@link
 * HouseholdLiveSyncFanout}), fixed by commit {@code b4218f7}.
 *
 * <p>The two {@code *Start_opensTheLiveSubscription...} tests below prove only that {@code
 * start()} opens without throwing — a wrong regex (a typo in a prefix, a missing {@code
 * household} branch) would still open fine and silently drop events, uncaught. Story 8.8 extends
 * this class with the delivery proof: both projectors, subscribed live against a real KurrentDB,
 * demonstrably receive and project an event from <strong>each</strong> of their two stream
 * prefixes, through the real read model on a PostgreSQL Testcontainer (mirrors {@link
 * HouseholdLiveSyncFanoutIntegrationTest}'s harness).
 */
@Testcontainers
class MultiPrefixKurrentDbSubscriptionRegressionTest {

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
    private static DataSource reachableDataSource;
    private static JdbcClient jdbcClient;

    private ShoppingListReadModelProjector shoppingListProjector;
    private ShoppingTripReadModelProjector shoppingTripProjector;
    private KurrentDbEventStore eventStore;
    private JdbcShoppingListReadModel shoppingListReadModel;
    private JdbcTripStoreReadModel tripStoreReadModel;

    @BeforeAll
    static void startInfrastructure() {
        String connectionString =
                "esdb://" + KURRENTDB.getHost() + ":" + KURRENTDB.getMappedPort(2113) + "?tls=false";
        client = KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow(connectionString));

        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl());
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        reachableDataSource = driverManagerDataSource;
        Flyway.configure().dataSource(reachableDataSource).load().migrate();
        jdbcClient = JdbcClient.create(reachableDataSource);
    }

    @AfterAll
    static void closeClient() {
        client.shutdown().join();
    }

    @BeforeEach
    void setUp() {
        eventStore = new KurrentDbEventStore(client);
        shoppingListReadModel = new JdbcShoppingListReadModel(jdbcClient);
        tripStoreReadModel = new JdbcTripStoreReadModel(jdbcClient);
    }

    @AfterEach
    void tearDown() {
        if (shoppingListProjector != null) {
            shoppingListProjector.stop();
        }
        if (shoppingTripProjector != null) {
            shoppingTripProjector.stop();
        }
    }

    @Test
    void shoppingListProjectorStart_opensTheLiveSubscriptionDespiteTwoStreamPrefixes() {
        // This test only asserts start() — an unreachable datasource is fine for that alone. But
        // stop() (see tearDown) only clears the running flag; it never cancels the open KurrentDB
        // subscription (pre-existing gap, deferred separately), so this fromStart subscription keeps
        // running after the test method returns and can still receive the delivery tests' later
        // list-/household- events. Any such event fails against this unreachable datasource inside
        // the projector's own try/catch (onEvent), which logs and skips it — it never surfaces here
        // or fails another test. The delivery tests stay independent regardless, since each uses
        // fresh random ids.
        DriverManagerDataSource unreachableDataSource = new DriverManagerDataSource();
        unreachableDataSource.setUrl("jdbc:postgresql://127.0.0.1:1/unreachable");
        JdbcClient unreachableJdbcClient = JdbcClient.create(unreachableDataSource);

        shoppingListProjector = new ShoppingListReadModelProjector(
                client,
                new JdbcShoppingListReadModel(unreachableJdbcClient),
                new JdbcItemReadModel(unreachableJdbcClient),
                new JdbcItemSuggestionReadModel(unreachableJdbcClient));

        shoppingListProjector.start();

        assertThat(shoppingListProjector.isRunning()).isTrue();
    }

    @Test
    void shoppingTripProjectorStart_opensTheLiveSubscriptionDespiteTwoStreamPrefixes() {
        // See the unreachable-datasource comment on the list-projector start() test above — the
        // same reasoning applies here (stop() never cancels the open subscription; any
        // trip-/household- event it later receives is logged and skipped against this datasource).
        DriverManagerDataSource unreachableDataSource = new DriverManagerDataSource();
        unreachableDataSource.setUrl("jdbc:postgresql://127.0.0.1:1/unreachable");
        JdbcClient unreachableJdbcClient = JdbcClient.create(unreachableDataSource);

        shoppingTripProjector =
                new ShoppingTripReadModelProjector(client, new JdbcTripStoreReadModel(unreachableJdbcClient));

        shoppingTripProjector.start();

        assertThat(shoppingTripProjector.isRunning()).isTrue();
    }

    @Test
    void shoppingListProjector_liveSubscription_deliversBothListAndHouseholdStreamEvents()
            throws InterruptedException {
        shoppingListProjector = new ShoppingListReadModelProjector(
                client,
                shoppingListReadModel,
                new JdbcItemReadModel(jdbcClient),
                new JdbcItemSuggestionReadModel(jdbcClient));
        shoppingListProjector.start();

        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        eventStore.append(
                AggregateVersion.initial(StreamId.forList(listId)),
                List.of(new ShoppingListCreated(
                        EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf"))),
                CommandId.generate());

        awaitTrue(
                () -> shoppingListReadModel.listsOf(householdId).size() == 1,
                "the list- stream event was never projected into the read model");

        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                List.of(new HouseholdDeleted(EventId.generate(), householdId, MemberId.generate())),
                CommandId.generate());

        awaitTrue(
                () -> shoppingListReadModel.listsOf(householdId).isEmpty(),
                "the household- stream HouseholdDeleted event never purged the list read model");
    }

    @Test
    void shoppingTripProjector_liveSubscription_deliversBothTripAndHouseholdStreamEvents()
            throws InterruptedException {
        shoppingTripProjector = new ShoppingTripReadModelProjector(client, tripStoreReadModel);
        shoppingTripProjector.start();

        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        TripId tripId = TripId.generate();
        eventStore.append(
                AggregateVersion.initial(StreamId.forTrip(tripId)),
                List.of(new TripStarted(
                        EventId.generate(), tripId, householdId, listId, List.of(StoreId.generate()))),
                CommandId.generate());

        awaitTrue(
                () -> !tripStoreReadModel.storesOf(tripId).isEmpty(),
                "the trip- stream event was never projected into the read model");

        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                List.of(new HouseholdDeleted(EventId.generate(), householdId, MemberId.generate())),
                CommandId.generate());

        awaitTrue(
                () -> tripStoreReadModel.storesOf(tripId).isEmpty(),
                "the household- stream HouseholdDeleted event never purged the trip-store read model");
    }
}
