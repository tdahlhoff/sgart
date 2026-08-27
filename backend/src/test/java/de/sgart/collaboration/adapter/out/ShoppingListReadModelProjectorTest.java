package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemMovedToList;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.readmodel.ItemSuggestionView;
import de.sgart.collaboration.domain.readmodel.ItemView;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.Unit;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
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
    private JdbcItemReadModel itemReadModel;
    private JdbcItemSuggestionReadModel itemSuggestionReadModel;

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
        jdbcClient.sql("TRUNCATE TABLE item_read_model").update();
        jdbcClient.sql("TRUNCATE TABLE item_suggestion_read_model").update();
        readModel = new JdbcShoppingListReadModel(jdbcClient);
        itemReadModel = new JdbcItemReadModel(jdbcClient);
        itemSuggestionReadModel = new JdbcItemSuggestionReadModel(jdbcClient);
        // Never connected: project(...) never touches the KurrentDB client (only start() does).
        KurrentDBClient neverConnectedClient =
                KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow("esdb://localhost:1?tls=false"));
        projector = new ShoppingListReadModelProjector(
                neverConnectedClient, readModel, itemReadModel, itemSuggestionReadModel);
    }

    @Test
    void projectingShoppingListCreatedYieldsANamedOpenListRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingList list = ShoppingList.create(
                listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());

        list.uncommittedEvents().forEach(projector::project);

        assertThat(readModel.listsOf(householdId))
                .containsExactly(new ShoppingListView(listId, new ShoppingListName("Wocheneinkauf"), ListStatus.OPEN, 0));
    }

    @Test
    void projectingShoppingListCreatedWithNoNameYieldsAnUnnamedOpenListRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingList list = ShoppingList.create(listId, householdId, null, CommandId.generate());

        list.uncommittedEvents().forEach(projector::project);

        assertThat(readModel.listsOf(householdId))
                .containsExactly(new ShoppingListView(listId, null, ListStatus.OPEN, 0));
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
                        new ShoppingListView(firstListId, new ShoppingListName("Getränke"), ListStatus.OPEN, 0),
                        new ShoppingListView(secondListId, new ShoppingListName("Getränke 2"), ListStatus.OPEN, 0));
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
                .containsExactly(new ShoppingListView(listId, new ShoppingListName("Getränke"), ListStatus.OPEN, 0));
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
                .containsExactly(new ShoppingListView(listId, new ShoppingListName("Getränke 2"), ListStatus.OPEN, 0));
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

    @Test
    void projectingItemAddedYieldsAnItemRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));

        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), new ItemNote("Bio"),
                Quantity.of(1, Unit.PIECE)));

        assertThat(itemReadModel.itemsOf(householdId, listId))
                .containsExactly(new ItemView(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE)));
    }

    @Test
    void projectingItemUpdatedChangesTheRowInPlace() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemUpdated(
                EventId.generate(), listId, itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.PIECE)));

        assertThat(itemReadModel.itemsOf(householdId, listId))
                .containsExactly(new ItemView(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.PIECE)));
    }

    @Test
    void projectingItemRemovedDeletesTheRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemRemoved(EventId.generate(), listId, itemId));

        assertThat(itemReadModel.itemsOf(householdId, listId)).isEmpty();
    }

    @Test
    void projectingItemMovedToListDeletesTheSourceRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId sourceListId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(sourceListId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, sourceListId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemMovedToList(
                EventId.generate(), householdId, sourceListId, itemId, targetListId, new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE)));

        assertThat(itemReadModel.itemsOf(householdId, sourceListId)).isEmpty();
    }

    @Test
    void aFullMoveLeavesExactlyOneRowUnderTheTargetListWithTheSameItemId() {
        // The end-to-end move outcome (AC2): the source ItemMovedToList removal followed by the
        // process manager's target ItemAdded — the item's identity survives the move.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId sourceListId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(sourceListId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(ShoppingList.create(targetListId, householdId, new ShoppingListName("Getränke"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, sourceListId, itemId, new ItemName("Milch"), new ItemNote("Bio"),
                Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemMovedToList(
                EventId.generate(), householdId, sourceListId, itemId, targetListId, new ItemName("Milch"),
                new ItemNote("Bio"), Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, targetListId, itemId, new ItemName("Milch"), new ItemNote("Bio"),
                Quantity.of(1, Unit.PIECE)));

        assertThat(itemReadModel.itemsOf(householdId, sourceListId)).isEmpty();
        assertThat(itemReadModel.itemsOf(householdId, targetListId))
                .containsExactly(new ItemView(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE)));
    }

    @Test
    void aListsItemCountReflectsItsCurrentItemRows() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));

        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, ItemId.generate(), new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, ItemId.generate(), new ItemName("Brot"), null,
                Quantity.of(1, Unit.PIECE)));

        assertThat(readModel.listsOf(householdId)).extracting(ShoppingListView::itemCount).containsExactly(2);
    }

    @Test
    void projectingItemAddedRecordsASuggestionRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));

        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, ItemId.generate(), new ItemName("Milch"), new ItemNote("Bio"),
                Quantity.of(2, Unit.LITRE)));

        assertThat(itemSuggestionReadModel.suggestionsOf(householdId))
                .containsExactly(new ItemSuggestionView(new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.LITRE)));
    }

    @Test
    void aSecondItemAddedOfTheSameNameWithDifferentCasingUpsertsToOneRowWithTheNewCasing() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, ItemId.generate(), new ItemName("milch"), null,
                Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, ItemId.generate(), new ItemName("Milch"), new ItemNote("Bio"),
                Quantity.of(2, Unit.LITRE)));

        assertThat(itemSuggestionReadModel.suggestionsOf(householdId))
                .containsExactly(new ItemSuggestionView(new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.LITRE)));
    }

    @Test
    void projectingItemUpdatedRefreshesTheSuggestionsLastUsedAttributes() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.LITRE)));

        projector.project(new ItemUpdated(
                EventId.generate(), listId, itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.LITRE)));

        assertThat(itemSuggestionReadModel.suggestionsOf(householdId))
                .containsExactly(new ItemSuggestionView(new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.LITRE)));
    }

    @Test
    void itemRemovedLeavesTheSuggestionRowIntact() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemRemoved(EventId.generate(), listId, itemId));

        assertThat(itemSuggestionReadModel.suggestionsOf(householdId))
                .containsExactly(new ItemSuggestionView(new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
    }

    @Test
    void itemMovedToListLeavesTheSuggestionRowIntact() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId sourceListId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(sourceListId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, sourceListId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemMovedToList(
                EventId.generate(), householdId, sourceListId, itemId, targetListId, new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE)));

        assertThat(itemSuggestionReadModel.suggestionsOf(householdId))
                .containsExactly(new ItemSuggestionView(new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
    }

    @Test
    void aCrossHouseholdNameNeverLeaksIntoAnotherHouseholdsSuggestions() {
        HouseholdId householdId = HouseholdId.generate();
        HouseholdId otherHousehold = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));

        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, ItemId.generate(), new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE)));

        assertThat(itemSuggestionReadModel.suggestionsOf(otherHousehold)).isEmpty();
    }

    @Test
    void anItemUpdatedWhoseItemRowIsNotProjectedYetStillUpdatesTheItemButRecordsNoSuggestion() {
        // The out-of-order/replay edge behind Cl. 5: ItemUpdated carries no householdId, so the
        // projector resolves it through item_read_model. With no row there yet the household is
        // unresolvable — the suggestion is skipped rather than guessed, and a later full replay
        // (which sees ItemAdded first) records it.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));

        projector.project(new ItemUpdated(
                EventId.generate(), listId, itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.LITRE)));

        assertThat(itemSuggestionReadModel.suggestionsOf(householdId)).isEmpty();
        assertThat(itemReadModel.itemsOf(householdId, listId)).isEmpty();
    }

    @Test
    void theSuggestionReadModelCarriesHouseholdContentOnly_neverAMemberOrCreatorColumn() {
        // AD-5/AD-6 + CLAUDE.md §5: an item's name/note/quantity is household content, not a
        // person. household_id is the only identifier on the row, so Epic-6 erasure can locate it
        // and nothing attributes a suggestion to whoever typed it.
        List<String> columnNames = JdbcClient.create(dataSource)
                .sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_name = 'item_suggestion_read_model'
                        """)
                .query(String.class)
                .list();

        assertThat(columnNames)
                .containsExactlyInAnyOrder(
                        "household_id", "normalized_name", "name", "note", "quantity_amount", "quantity_unit");
    }
}
