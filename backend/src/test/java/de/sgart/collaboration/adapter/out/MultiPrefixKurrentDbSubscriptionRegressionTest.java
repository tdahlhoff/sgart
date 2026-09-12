package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression guard for LD-2, surfaced 2026-09-12 running the real stack for the first time
 * (docs/first-real-world-test.md): {@link ShoppingListReadModelProjector} and {@link
 * ShoppingTripReadModelProjector} each built their subscription filter with two chained {@code
 * addStreamNamePrefix} calls, which the kurrentdb-client (1.2.1) {@code SubscriptionFilterBuilder}
 * rejects on the second call with {@code IllegalStateException("Filter type is already set to
 * STREAM")} — a defect neither class's own {@code *Test.java} caught, because those drive {@code
 * project(...)} directly against a Postgres Testcontainer and never open the real subscription.
 * Both now build the filter with a single regular expression instead (matching {@link
 * HouseholdLiveSyncFanout}); this test proves {@code start()} actually opens the live subscription
 * against a real KurrentDB without throwing.
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

    private static KurrentDBClient client;
    private static JdbcClient jdbcClient;

    private ShoppingListReadModelProjector shoppingListProjector;
    private ShoppingTripReadModelProjector shoppingTripProjector;

    @BeforeAll
    static void startInfrastructure() {
        String connectionString =
                "esdb://" + KURRENTDB.getHost() + ":" + KURRENTDB.getMappedPort(2113) + "?tls=false";
        client = KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow(connectionString));

        // Never queried by this test — start()/subscribe() alone perform no read-model I/O — so an
        // unreachable datasource is fine here (mirrors ContextLoadsWithoutPostgresTest).
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:postgresql://127.0.0.1:1/unreachable");
        jdbcClient = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void closeClient() {
        client.shutdown().join();
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
        shoppingListProjector = new ShoppingListReadModelProjector(
                client,
                new JdbcShoppingListReadModel(jdbcClient),
                new JdbcItemReadModel(jdbcClient),
                new JdbcItemSuggestionReadModel(jdbcClient));

        shoppingListProjector.start();

        assertThat(shoppingListProjector.isRunning()).isTrue();
    }

    @Test
    void shoppingTripProjectorStart_opensTheLiveSubscriptionDespiteTwoStreamPrefixes() {
        shoppingTripProjector =
                new ShoppingTripReadModelProjector(client, new JdbcTripStoreReadModel(jdbcClient));

        shoppingTripProjector.start();

        assertThat(shoppingTripProjector.isRunning()).isTrue();
    }
}
