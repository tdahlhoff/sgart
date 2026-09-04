package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.event.StoreAddedToTrip;
import de.sgart.collaboration.domain.event.TripCompleted;
import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers integration test against real PostgreSQL (reusing the Story 1.6 harness) — the
 * third CQRS read side (Story 3.2, Cl. 2/4): {@link ShoppingTripReadModelProjector} folding {@code
 * TripStarted}/{@code StoreAddedToTrip} into {@link JdbcTripStoreReadModel}. Proves the {@code
 * fromStart} promise: a {@code TripStarted} event (raised in Story 3.1) still projects its stores
 * when driven directly here, exactly the way a real {@code fromStart} catch-up would retroactively
 * recover it. Drives the projector's {@code project(...)} method directly — deterministic and fast
 * — rather than its live KurrentDB subscription (mirrors {@code ShoppingListReadModelProjectorTest}).
 * Owns its own container lifecycle; never points at the dev compose PostgreSQL.
 */
@Testcontainers
class ShoppingTripReadModelProjectorTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DataSource dataSource;

    private ShoppingTripReadModelProjector projector;
    private JdbcTripStoreReadModel tripStoreReadModel;

    @BeforeAll
    static void migrateDatabase() {
        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl());
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void setUp() {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("TRUNCATE TABLE trip_store_read_model").update();
        tripStoreReadModel = new JdbcTripStoreReadModel(jdbcClient);
        // Never connected: project(...) never touches the KurrentDB client (only start() does).
        KurrentDBClient neverConnectedClient =
                KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow("esdb://localhost:1?tls=false"));
        projector = new ShoppingTripReadModelProjector(neverConnectedClient, tripStoreReadModel);
    }

    @Test
    void aTripStartedProjectsItsStoresInOrder_retroactively() {
        // Story 3.2, Cl. 2/4 — fromStart retroactively projects a 3.1-created TripStarted stream.
        TripId tripId = TripId.generate();
        StoreId edeka = StoreId.generate();
        StoreId netto = StoreId.generate();

        projector.project(new TripStarted(
                EventId.generate(),
                tripId,
                HouseholdId.generate(),
                ShoppingListId.generate(),
                List.of(edeka, netto)));

        assertThat(tripStoreReadModel.storesOf(tripId)).containsExactly(edeka, netto);
    }

    @Test
    void aStoreAddedToTripAppendsAStore() {
        TripId tripId = TripId.generate();
        StoreId edeka = StoreId.generate();
        StoreId netto = StoreId.generate();
        projector.project(new TripStarted(
                EventId.generate(), tripId, HouseholdId.generate(), ShoppingListId.generate(), List.of(edeka)));

        projector.project(new StoreAddedToTrip(EventId.generate(), tripId, HouseholdId.generate(), netto));

        assertThat(tripStoreReadModel.storesOf(tripId)).containsExactly(edeka, netto);
    }

    @Test
    void reProjectingTheSameEventsIsIdempotent() {
        TripId tripId = TripId.generate();
        StoreId edeka = StoreId.generate();
        TripStarted started = new TripStarted(
                EventId.generate(), tripId, HouseholdId.generate(), ShoppingListId.generate(), List.of(edeka));

        projector.project(started);
        projector.project(started);

        assertThat(tripStoreReadModel.storesOf(tripId)).containsExactly(edeka);
    }

    @Test
    void projectingTripCompletedDeletesTheTripStoreRows() {
        TripId tripId = TripId.generate();
        StoreId edeka = StoreId.generate();
        projector.project(new TripStarted(
                EventId.generate(), tripId, HouseholdId.generate(), ShoppingListId.generate(), List.of(edeka)));

        projector.project(new TripCompleted(EventId.generate(), tripId, HouseholdId.generate(), ShoppingListId.generate()));

        assertThat(tripStoreReadModel.storesOf(tripId)).isEmpty();
    }

    @Test
    void twoTripsStoreSetsNeverMix() {
        // Isolation (retro Action 4) — two trips' stores stay independent.
        TripId tripAId = TripId.generate();
        TripId tripBId = TripId.generate();
        StoreId edeka = StoreId.generate();
        StoreId netto = StoreId.generate();

        projector.project(new TripStarted(
                EventId.generate(), tripAId, HouseholdId.generate(), ShoppingListId.generate(), List.of(edeka)));
        projector.project(new TripStarted(
                EventId.generate(), tripBId, HouseholdId.generate(), ShoppingListId.generate(), List.of(netto)));

        assertThat(tripStoreReadModel.storesOf(tripAId)).containsExactly(edeka);
        assertThat(tripStoreReadModel.storesOf(tripBId)).containsExactly(netto);
    }
}
