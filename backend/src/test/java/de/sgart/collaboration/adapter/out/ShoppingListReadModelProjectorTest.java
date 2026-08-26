package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
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
 * second CQRS read side (Story 2.1, AC1/AC2/AC4): {@link ShoppingListReadModelProjector} folding
 * {@code ShoppingListCreated}/{@code ShoppingListRenamed} into {@link JdbcShoppingListReadModel}.
 * Drives the projector's {@code project(...)} method directly — deterministic and fast — rather
 * than its live KurrentDB subscription (mirroring {@code HouseholdReadModelProjectorTest}). Owns
 * its own container lifecycle; never points at the dev compose PostgreSQL.
 */
@Testcontainers
class ShoppingListReadModelProjectorTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DataSource dataSource;

    private ShoppingListReadModelProjector projector;
    private JdbcShoppingListReadModel readModel;

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
        jdbcClient.sql("TRUNCATE TABLE shopping_list_read_model").update();
        readModel = new JdbcShoppingListReadModel(jdbcClient);
        // Never connected: project(...) never touches the KurrentDB client (only start() does).
        KurrentDBClient neverConnectedClient =
                KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow("esdb://localhost:1?tls=false"));
        projector = new ShoppingListReadModelProjector(neverConnectedClient, readModel);
    }

    @Test
    void projectingShoppingListCreatedYieldsANamedOpenListRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingList list = ShoppingList.create(
                listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());

        list.uncommittedEvents().forEach(projector::project);

        assertThat(readModel.listsOf(householdId))
                .containsExactly(new ShoppingListView(listId, new ShoppingListName("Wocheneinkauf"), ListStatus.OPEN));
    }

    @Test
    void projectingShoppingListCreatedWithNoNameYieldsAnUnnamedOpenListRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingList list = ShoppingList.create(listId, householdId, null, CommandId.generate());

        list.uncommittedEvents().forEach(projector::project);

        assertThat(readModel.listsOf(householdId))
                .containsExactly(new ShoppingListView(listId, null, ListStatus.OPEN));
    }

    @Test
    void projectingShoppingListRenamedUpdatesOnlyTheNameAndPreservesCreationOrder() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId firstListId = ShoppingListId.generate();
        ShoppingListId secondListId = ShoppingListId.generate();
        projector.project(ShoppingList.create(firstListId, householdId, new ShoppingListName("Getränke"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(ShoppingList.create(secondListId, householdId, null, CommandId.generate())
                .uncommittedEvents()
                .get(0));

        projector.project(new ShoppingListRenamed(EventId.generate(), secondListId, new ShoppingListName("Getränke 2")));

        assertThat(readModel.listsOf(householdId))
                .containsExactly(
                        new ShoppingListView(firstListId, new ShoppingListName("Getränke"), ListStatus.OPEN),
                        new ShoppingListView(secondListId, new ShoppingListName("Getränke 2"), ListStatus.OPEN));
    }

    @Test
    void twoListsInOneHouseholdBothProjectAndComeBackInCreationOrder() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId firstListId = ShoppingListId.generate();
        ShoppingListId secondListId = ShoppingListId.generate();

        projector.project(ShoppingList.create(firstListId, householdId, new ShoppingListName("Getränke"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(ShoppingList.create(secondListId, householdId, null, CommandId.generate())
                .uncommittedEvents()
                .get(0));

        assertThat(readModel.listsOf(householdId))
                .extracting(ShoppingListView::listId)
                .containsExactly(firstListId, secondListId);
    }

    @Test
    void reProjectingShoppingListCreatedIsIdempotent() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        var created = ShoppingList.create(listId, householdId, new ShoppingListName("Getränke"), CommandId.generate())
                .uncommittedEvents()
                .get(0);

        projector.project(created);
        projector.project(created);

        assertThat(readModel.listsOf(householdId))
                .containsExactly(new ShoppingListView(listId, new ShoppingListName("Getränke"), ListStatus.OPEN));
    }

    @Test
    void reProjectingShoppingListCreatedAfterARenameDoesNotRevertTheName() {
        // A fromStart replay re-applies Created after Renamed; the creation-time name must not win
        // back over the rename (the read model's name is owned solely by renameList).
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        var created = ShoppingList.create(listId, householdId, new ShoppingListName("Getränke"), CommandId.generate())
                .uncommittedEvents()
                .get(0);
        projector.project(created);
        projector.project(new ShoppingListRenamed(EventId.generate(), listId, new ShoppingListName("Getränke 2")));

        projector.project(created);

        assertThat(readModel.listsOf(householdId))
                .containsExactly(new ShoppingListView(listId, new ShoppingListName("Getränke 2"), ListStatus.OPEN));
    }

    @Test
    void listsOfReturnsOnlyTheGivenHouseholdsLists() {
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();
        ShoppingListId firstHouseholdListId = ShoppingListId.generate();
        ShoppingListId secondHouseholdListId = ShoppingListId.generate();
        projector.project(ShoppingList.create(
                        firstHouseholdListId, firstHousehold, new ShoppingListName("Getränke"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(ShoppingList.create(
                        secondHouseholdListId, secondHousehold, new ShoppingListName("Baumarkt"), CommandId.generate())
                .uncommittedEvents()
                .get(0));

        assertThat(readModel.listsOf(firstHousehold))
                .extracting(ShoppingListView::listId)
                .containsExactly(firstHouseholdListId);
    }
}
