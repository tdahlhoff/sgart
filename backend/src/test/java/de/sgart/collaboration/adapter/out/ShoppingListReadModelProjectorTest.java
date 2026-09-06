package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ItemStatus;
import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemAssignedToStore;
import de.sgart.collaboration.domain.event.ItemCheckedOff;
import de.sgart.collaboration.domain.event.ItemDiscarded;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemRerouted;
import de.sgart.collaboration.domain.event.ItemTransferCancelled;
import de.sgart.collaboration.domain.event.ItemTransferConfirmed;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
import de.sgart.collaboration.domain.event.ItemUnchecked;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.event.TripCompletedForList;
import de.sgart.collaboration.domain.event.TripStartedForList;
import de.sgart.collaboration.domain.readmodel.ItemSuggestionView;
import de.sgart.collaboration.domain.readmodel.ItemView;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.collaboration.domain.TransferCancellationReason;
import de.sgart.collaboration.domain.TransferOrigin;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
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
                .containsExactly(new ShoppingListView(listId, new ShoppingListName("Wocheneinkauf"), ListStatus.OPEN, 0, null));
    }

    @Test
    void projectingShoppingListCreatedWithNoNameYieldsAnUnnamedOpenListRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingList list = ShoppingList.create(listId, householdId, null, CommandId.generate());

        list.uncommittedEvents().forEach(projector::project);

        assertThat(readModel.listsOf(householdId))
                .containsExactly(new ShoppingListView(listId, null, ListStatus.OPEN, 0, null));
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
                        new ShoppingListView(firstListId, new ShoppingListName("Getränke"), ListStatus.OPEN, 0, null),
                        new ShoppingListView(secondListId, new ShoppingListName("Getränke 2"), ListStatus.OPEN, 0, null));
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
                .containsExactly(new ShoppingListView(listId, new ShoppingListName("Getränke"), ListStatus.OPEN, 0, null));
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
                .containsExactly(new ShoppingListView(listId, new ShoppingListName("Getränke 2"), ListStatus.OPEN, 0, null));
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
                .containsExactly(new ItemView(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), null, ItemStatus.OPEN, false));
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
                .containsExactly(new ItemView(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.PIECE), null, ItemStatus.OPEN, false));
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
    void projectingItemTransferInitiatedSetsTransferPendingAndKeepsTheRow() {
        // Story 3.6, AC1/AC5 — the reserved sub-state, unlike the retired ItemMovedToList/
        // ItemPostponedToList, KEEPS the row on the source rather than removing it.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId sourceListId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(sourceListId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, sourceListId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemTransferInitiated(
                EventId.generate(), householdId, sourceListId, itemId, targetListId, new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE), TransferOrigin.PLANNING_MOVE));

        List<ItemView> items = itemReadModel.itemsOf(householdId, sourceListId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).transferPending()).isTrue();
    }

    @Test
    void projectingItemTransferConfirmedRemovesTheRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemTransferInitiated(
                EventId.generate(), householdId, listId, itemId, targetListId, new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE), TransferOrigin.PLANNING_MOVE));

        projector.project(new ItemTransferConfirmed(EventId.generate(), listId, itemId));

        assertThat(itemReadModel.itemsOf(householdId, listId)).isEmpty();
    }

    @Test
    void projectingItemTransferCancelledClearsTheFlagAndKeepsTheItemOnTheSource() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemTransferInitiated(
                EventId.generate(), householdId, listId, itemId, targetListId, new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE), TransferOrigin.IN_TRIP_POSTPONE));

        projector.project(new ItemTransferCancelled(
                EventId.generate(), listId, itemId, TransferCancellationReason.TARGET_NOT_OPEN));

        List<ItemView> items = itemReadModel.itemsOf(householdId, listId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).transferPending()).isFalse();
    }

    @Test
    void aFullTransferLeavesExactlyOneRowUnderTheTargetListWithTheSameItemId() {
        // The end-to-end transfer outcome (AC2): the source reserves (ItemTransferInitiated), the
        // process manager's target ItemAdded lands, then confirm removes the source row — the
        // item's identity survives the transfer.
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

        projector.project(new ItemTransferInitiated(
                EventId.generate(), householdId, sourceListId, itemId, targetListId, new ItemName("Milch"),
                new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), TransferOrigin.PLANNING_MOVE));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, targetListId, itemId, new ItemName("Milch"), new ItemNote("Bio"),
                Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemTransferConfirmed(EventId.generate(), sourceListId, itemId));

        assertThat(itemReadModel.itemsOf(householdId, sourceListId)).isEmpty();
        assertThat(itemReadModel.itemsOf(householdId, targetListId))
                .containsExactly(new ItemView(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), null, ItemStatus.OPEN, false));
    }

    @Test
    void aTransferResetsTheTargetRowsStoreAndStatusToTheItemsBirthState() {
        // Regression (Story 3.6 code review): the target aggregate adds the item with a bare
        // ItemAdded (assignedStore=null, status=OPEN), so the relocated read-model row must not carry
        // the source's store assignment or in-trip status forward — the DO UPDATE relocation resets
        // both, matching the write model and the pre-3.6 delete-then-reinsert behavior.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId sourceListId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        projector.project(ShoppingList.create(sourceListId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(ShoppingList.create(targetListId, householdId, new ShoppingListName("Getränke"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, sourceListId, itemId, new ItemName("Milch"), new ItemNote("Bio"),
                Quantity.of(1, Unit.PIECE)));
        // The source item carries a store assignment and a non-OPEN status before it is transferred.
        projector.project(new ItemAssignedToStore(EventId.generate(), householdId, sourceListId, itemId, storeId));
        projector.project(new ItemCheckedOff(EventId.generate(), householdId, sourceListId, itemId));

        projector.project(new ItemTransferInitiated(
                EventId.generate(), householdId, sourceListId, itemId, targetListId, new ItemName("Milch"),
                new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), TransferOrigin.PLANNING_MOVE));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, targetListId, itemId, new ItemName("Milch"), new ItemNote("Bio"),
                Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemTransferConfirmed(EventId.generate(), sourceListId, itemId));

        assertThat(itemReadModel.itemsOf(householdId, targetListId))
                .containsExactly(new ItemView(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), null, ItemStatus.OPEN, false));
    }

    @Test
    void anItemTransferInitiatedNeverLeaksAcrossHouseholds() {
        HouseholdId householdId = HouseholdId.generate();
        HouseholdId otherHousehold = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemTransferInitiated(
                EventId.generate(), householdId, listId, itemId, ShoppingListId.generate(), new ItemName("Milch"),
                null, Quantity.of(1, Unit.PIECE), TransferOrigin.PLANNING_MOVE));

        assertThat(itemReadModel.itemsOf(otherHousehold, listId)).isEmpty();
    }

    @Test
    void reProjectingItemTransferInitiatedIsIdempotent() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        ItemTransferInitiated initiated = new ItemTransferInitiated(
                EventId.generate(), householdId, listId, itemId, targetListId, new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE), TransferOrigin.PLANNING_MOVE);

        projector.project(initiated);
        projector.project(initiated);

        List<ItemView> items = itemReadModel.itemsOf(householdId, listId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).transferPending()).isTrue();
    }

    @Test
    void reProjectingItemTransferConfirmedIsIdempotent() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemTransferInitiated(
                EventId.generate(), householdId, listId, itemId, targetListId, new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE), TransferOrigin.PLANNING_MOVE));
        ItemTransferConfirmed confirmed = new ItemTransferConfirmed(EventId.generate(), listId, itemId);

        projector.project(confirmed);
        projector.project(confirmed);

        assertThat(itemReadModel.itemsOf(householdId, listId)).isEmpty();
    }

    @Test
    void reProjectingItemTransferCancelledIsIdempotent() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemTransferInitiated(
                EventId.generate(), householdId, listId, itemId, targetListId, new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE), TransferOrigin.PLANNING_MOVE));
        ItemTransferCancelled cancelled =
                new ItemTransferCancelled(EventId.generate(), listId, itemId, TransferCancellationReason.TARGET_GONE);

        projector.project(cancelled);
        projector.project(cancelled);

        List<ItemView> items = itemReadModel.itemsOf(householdId, listId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).transferPending()).isFalse();
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
                .containsExactly(new ItemSuggestionView(new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.LITRE), null));
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
                .containsExactly(new ItemSuggestionView(new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.LITRE), null));
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
                .containsExactly(new ItemSuggestionView(new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.LITRE), null));
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
                .containsExactly(new ItemSuggestionView(new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), null));
    }

    @Test
    void itemTransferConfirmedLeavesTheSuggestionRowIntact() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId sourceListId = ShoppingListId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(sourceListId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, sourceListId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemTransferInitiated(
                EventId.generate(), householdId, sourceListId, itemId, targetListId, new ItemName("Milch"), null,
                Quantity.of(1, Unit.PIECE), TransferOrigin.PLANNING_MOVE));

        projector.project(new ItemTransferConfirmed(EventId.generate(), sourceListId, itemId));

        assertThat(itemSuggestionReadModel.suggestionsOf(householdId))
                .containsExactly(new ItemSuggestionView(new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), null));
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
    void projectingItemAssignedToStoreSetsTheItemsStoreIdAndTheSuggestionsDefaultStoreId() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), new ItemNote("Bio"),
                Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemAssignedToStore(EventId.generate(), householdId, listId, itemId, storeId));

        assertThat(itemReadModel.itemsOf(householdId, listId))
                .containsExactly(
                        new ItemView(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), storeId, ItemStatus.OPEN, false));
        assertThat(itemSuggestionReadModel.suggestionsOf(householdId))
                .containsExactly(
                        new ItemSuggestionView(new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), storeId));
    }

    @Test
    void reassigningAnItemOverwritesBothTheItemsAndTheSuggestionsStoreId() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        StoreId firstStoreId = StoreId.generate();
        StoreId secondStoreId = StoreId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemAssignedToStore(EventId.generate(), householdId, listId, itemId, firstStoreId));

        projector.project(new ItemAssignedToStore(EventId.generate(), householdId, listId, itemId, secondStoreId));

        assertThat(itemReadModel.itemsOf(householdId, listId)).extracting(ItemView::storeId).containsExactly(secondStoreId);
        assertThat(itemSuggestionReadModel.suggestionsOf(householdId))
                .extracting(ItemSuggestionView::defaultStore)
                .containsExactly(secondStoreId);
    }

    @Test
    void editingAnAssignedItemLeavesTheStoreIdAndDefaultStoreIdIntact() {
        // Cl. 7 regression trap: ItemUpdated's updateItem/recordUsage must never touch the store
        // columns — only ItemAssignedToStore's projection writes them.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemAssignedToStore(EventId.generate(), householdId, listId, itemId, storeId));

        projector.project(new ItemUpdated(
                EventId.generate(), listId, itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.PIECE)));

        assertThat(itemReadModel.itemsOf(householdId, listId)).extracting(ItemView::storeId).containsExactly(storeId);
        assertThat(itemSuggestionReadModel.suggestionsOf(householdId))
                .extracting(ItemSuggestionView::defaultStore)
                .containsExactly(storeId);
    }

    @Test
    void anAssignWhoseItemRowIsMissingStillSetsTheItemStoreIdAndSkipsTheSuggestion() {
        // Out-of-order/replay edge (Cl. 6): nameOf comes back empty because ItemAdded hasn't been
        // projected yet — the suggestion write is skipped, but the item's own store_id still updates.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));

        projector.project(new ItemAssignedToStore(EventId.generate(), householdId, listId, itemId, storeId));

        assertThat(itemReadModel.itemsOf(householdId, listId)).isEmpty();
        assertThat(itemSuggestionReadModel.suggestionsOf(householdId)).isEmpty();
    }

    @Test
    void anItemAssignedToStoreNeverLeaksAcrossHouseholds() {
        HouseholdId householdId = HouseholdId.generate();
        HouseholdId otherHousehold = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemAssignedToStore(EventId.generate(), householdId, listId, itemId, StoreId.generate()));

        assertThat(itemReadModel.itemsOf(otherHousehold, listId)).isEmpty();
        assertThat(itemSuggestionReadModel.suggestionsOf(otherHousehold)).isEmpty();
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
                        "household_id", "normalized_name", "name", "note", "quantity_amount", "quantity_unit",
                        "default_store_id");
    }

    @Test
    void projectingTripStartedForListFlipsTheListStatusToInTrip() {
        // Story 3.1, AC5.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));

        TripId tripId = TripId.generate();
        projector.project(new TripStartedForList(
                EventId.generate(), householdId, listId, tripId, List.of(StoreId.generate())));

        assertThat(readModel.listsOf(householdId))
                .containsExactly(new ShoppingListView(
                        listId, new ShoppingListName("Wocheneinkauf"), ListStatus.IN_TRIP, 0, tripId));
    }

    @Test
    void aTripStartedForListNeverFlipsAListInAnotherHousehold() {
        // Story 3.1, AC7 — read-side isolation (retro Action 4).
        HouseholdId householdA = HouseholdId.generate();
        HouseholdId householdB = HouseholdId.generate();
        ShoppingListId listAId = ShoppingListId.generate();
        ShoppingListId listBId = ShoppingListId.generate();
        projector.project(ShoppingList.create(listAId, householdA, new ShoppingListName("A"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(ShoppingList.create(listBId, householdB, new ShoppingListName("B"), CommandId.generate())
                .uncommittedEvents()
                .get(0));

        TripId tripId = TripId.generate();
        projector.project(new TripStartedForList(
                EventId.generate(), householdA, listAId, tripId, List.of(StoreId.generate())));

        assertThat(readModel.listsOf(householdA))
                .containsExactly(new ShoppingListView(listAId, new ShoppingListName("A"), ListStatus.IN_TRIP, 0, tripId));
        assertThat(readModel.listsOf(householdB))
                .containsExactly(new ShoppingListView(listBId, new ShoppingListName("B"), ListStatus.OPEN, 0, null));
    }

    @Test
    void reProjectingTripStartedForListIsIdempotent() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        TripId tripId = TripId.generate();
        TripStartedForList started = new TripStartedForList(
                EventId.generate(), householdId, listId, tripId, List.of(StoreId.generate()));

        projector.project(started);
        projector.project(started);

        assertThat(readModel.listsOf(householdId))
                .containsExactly(new ShoppingListView(
                        listId, new ShoppingListName("Wocheneinkauf"), ListStatus.IN_TRIP, 0, tripId));
    }

    @Test
    void anItemReroutedUpdatesTheItemsStoreId_andLeavesTheSuggestionsDefaultStoreIdUntouched() {
        // Story 3.2, AC2, Cl. 6 — reroute converges on item_read_model.store_id only; the
        // suggestion's default_store_id (planning) is untouched.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        StoreId planningStore = StoreId.generate();
        StoreId tripStore = StoreId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(),
                householdId,
                listId,
                itemId,
                new ItemName("Milch"),
                null,
                Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemAssignedToStore(EventId.generate(), householdId, listId, itemId, planningStore));

        projector.project(new ItemRerouted(EventId.generate(), householdId, listId, itemId, tripStore));

        List<ItemView> items = itemReadModel.itemsOf(householdId, listId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).storeId()).isEqualTo(tripStore);
        List<ItemSuggestionView> suggestions = itemSuggestionReadModel.suggestionsOf(householdId);
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).defaultStore()).isEqualTo(planningStore);
    }

    @Test
    void projectingItemCheckedOffSetsStatusToDone() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemCheckedOff(EventId.generate(), householdId, listId, itemId));

        assertThat(itemReadModel.itemsOf(householdId, listId).get(0).status()).isEqualTo(ItemStatus.DONE);
    }

    @Test
    void projectingItemUncheckedSetsStatusToOpen() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemCheckedOff(EventId.generate(), householdId, listId, itemId));

        projector.project(new ItemUnchecked(EventId.generate(), householdId, listId, itemId));

        assertThat(itemReadModel.itemsOf(householdId, listId).get(0).status()).isEqualTo(ItemStatus.OPEN);
    }

    @Test
    void projectingItemDiscardedSetsStatusToDiscarded() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemDiscarded(EventId.generate(), householdId, listId, itemId));

        assertThat(itemReadModel.itemsOf(householdId, listId).get(0).status()).isEqualTo(ItemStatus.DISCARDED);
    }

    @Test
    void projectingAnInTripPostponeInitiatedSetsTransferPendingAndKeepsTheRow() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemTransferInitiated(EventId.generate(), householdId, listId, itemId,
                ShoppingListId.generate(), new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE),
                TransferOrigin.IN_TRIP_POSTPONE));

        List<ItemView> items = itemReadModel.itemsOf(householdId, listId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).transferPending()).isTrue();
    }

    @Test
    void projectingItemCheckedOffDoesNotLeakStatusAcrossHouseholds() {
        HouseholdId householdAId = HouseholdId.generate();
        HouseholdId householdBId = HouseholdId.generate();
        ShoppingListId listAId = ShoppingListId.generate();
        ShoppingListId listBId = ShoppingListId.generate();
        ItemId itemAId = ItemId.generate();
        ItemId itemBId = ItemId.generate();
        projector.project(ShoppingList.create(listAId, householdAId, new ShoppingListName("Liste A"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(ShoppingList.create(listBId, householdBId, new ShoppingListName("Liste B"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new ItemAdded(EventId.generate(), householdAId, listAId, itemAId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemAdded(EventId.generate(), householdBId, listBId, itemBId, new ItemName("Brot"), null, Quantity.of(1, Unit.PIECE)));

        projector.project(new ItemCheckedOff(EventId.generate(), householdAId, listAId, itemAId));

        assertThat(itemReadModel.itemsOf(householdAId, listAId).get(0).status()).isEqualTo(ItemStatus.DONE);
        assertThat(itemReadModel.itemsOf(householdBId, listBId).get(0).status()).isEqualTo(ItemStatus.OPEN);
    }

    @Test
    void reProjectingItemReroutedIsIdempotent() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate())
                .uncommittedEvents()
                .get(0));
        projector.project(new ItemAdded(
                EventId.generate(),
                householdId,
                listId,
                itemId,
                new ItemName("Milch"),
                null,
                Quantity.of(1, Unit.PIECE)));
        ItemRerouted rerouted = new ItemRerouted(EventId.generate(), householdId, listId, itemId, storeId);

        projector.project(rerouted);
        projector.project(rerouted);

        assertThat(itemReadModel.itemsOf(householdId, listId).get(0).storeId()).isEqualTo(storeId);
    }

    @Test
    void reroutingAfterACheckOffLeavesTheStatusDone() {
        // Cl. 4 regression: only the status events write status — a later ItemRerouted must update
        // storeId while leaving DONE untouched.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        projector.project(new ItemCheckedOff(EventId.generate(), householdId, listId, itemId));

        projector.project(new ItemRerouted(EventId.generate(), householdId, listId, itemId, storeId));

        assertThat(itemReadModel.itemsOf(householdId, listId).get(0).status()).isEqualTo(ItemStatus.DONE);
        assertThat(itemReadModel.itemsOf(householdId, listId).get(0).storeId()).isEqualTo(storeId);
    }

    @Test
    void projectingTripCompletedForListFlipsTheListStatusToDone() {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        TripId tripId = TripId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new TripStartedForList(EventId.generate(), householdId, listId, tripId, List.of(StoreId.generate())));

        projector.project(new TripCompletedForList(EventId.generate(), householdId, listId, tripId));

        List<ShoppingListView> lists = readModel.listsOf(householdId);
        assertThat(lists).hasSize(1);
        assertThat(lists.get(0).status()).isEqualTo(ListStatus.DONE);
        assertThat(lists.get(0).activeTripId()).isNull();
    }

    @Test
    void aCompletedListAppearsInDoneStatusAndNotInOpenOrInTrip() {
        // Cl. 9(a)/Action 10 — the completed list lands in the DONE set and leaves the Open/In-Trip
        // set (proxy for ListDoneLists ⊃ DONE / ListOpenLists ⊃ OPEN|IN_TRIP filters).
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        TripId tripId = TripId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Einkauf"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new TripStartedForList(EventId.generate(), householdId, listId, tripId, List.of(StoreId.generate())));
        projector.project(new TripCompletedForList(EventId.generate(), householdId, listId, tripId));

        List<ShoppingListView> all = readModel.listsOf(householdId);
        long doneCount = all.stream().filter(l -> l.status() == ListStatus.DONE).count();
        long openOrInTripCount = all.stream().filter(l -> l.status() == ListStatus.OPEN || l.status() == ListStatus.IN_TRIP).count();
        assertThat(doneCount).isEqualTo(1);
        assertThat(openOrInTripCount).isEqualTo(0);
    }

    @Test
    void completionSequenceDoesNotLeakStatusToAnotherHousehold() {
        // Action 10 — two-household isolation: completing householdA's list must not touch householdB's.
        HouseholdId householdA = HouseholdId.generate();
        HouseholdId householdB = HouseholdId.generate();
        ShoppingListId listAId = ShoppingListId.generate();
        ShoppingListId listBId = ShoppingListId.generate();
        TripId tripId = TripId.generate();
        projector.project(ShoppingList.create(listAId, householdA, new ShoppingListName("A"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(ShoppingList.create(listBId, householdB, new ShoppingListName("B"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new TripStartedForList(EventId.generate(), householdA, listAId, tripId, List.of(StoreId.generate())));

        projector.project(new TripCompletedForList(EventId.generate(), householdA, listAId, tripId));

        assertThat(readModel.listsOf(householdA).get(0).status()).isEqualTo(ListStatus.DONE);
        assertThat(readModel.listsOf(householdB).get(0).status()).isEqualTo(ListStatus.OPEN);
    }

    @Test
    void reProjectingTripCompletedForListIsIdempotent() {
        // Action 10 — replay idempotency for the completion sequence.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        TripId tripId = TripId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new TripStartedForList(EventId.generate(), householdId, listId, tripId, List.of(StoreId.generate())));
        TripCompletedForList completedEvent = new TripCompletedForList(EventId.generate(), householdId, listId, tripId);

        projector.project(completedEvent);
        projector.project(completedEvent);

        List<ShoppingListView> lists = readModel.listsOf(householdId);
        assertThat(lists).hasSize(1);
        assertThat(lists.get(0).status()).isEqualTo(ListStatus.DONE);
        assertThat(lists.get(0).activeTripId()).isNull();
    }

    @Test
    void reProjectingItemCheckedOffIsIdempotent() {
        // Replay idempotency for the status column: a redelivered ItemCheckedOff leaves status DONE.
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        ItemId itemId = ItemId.generate();
        projector.project(ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate()).uncommittedEvents().get(0));
        projector.project(new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)));
        ItemCheckedOff checkedOff = new ItemCheckedOff(EventId.generate(), householdId, listId, itemId);

        projector.project(checkedOff);
        projector.project(checkedOff);

        assertThat(itemReadModel.itemsOf(householdId, listId)).hasSize(1);
        assertThat(itemReadModel.itemsOf(householdId, listId).get(0).status()).isEqualTo(ItemStatus.DONE);
    }
}
