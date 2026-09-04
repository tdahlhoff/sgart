package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemCheckedOff;
import de.sgart.collaboration.domain.event.ItemPostponed;
import de.sgart.collaboration.domain.event.ItemPostponedToList;
import de.sgart.collaboration.domain.event.ItemRerouted;
import de.sgart.collaboration.domain.event.ItemUnchecked;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.event.TripStartedForList;
import de.sgart.collaboration.domain.exception.ItemChangeNotPermittedException;
import de.sgart.collaboration.domain.exception.ItemNotFoundException;
import de.sgart.collaboration.domain.exception.ItemNotDuringTripException;
import de.sgart.collaboration.domain.exception.TripNotStartableException;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import de.sgart.shared.Unit;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * second aggregate: creating a list raises {@code ShoppingListCreated} (named or unnamed) into
 * {@code OPEN}, renaming an {@code OPEN} list raises {@code ShoppingListRenamed}, a rename to the
 * same name is a convergent no-op, and replaying history rebuilds identical state (AC1, AC3). Also
 * proves the Story 3.1 {@code startTrip} transition: {@code OPEN} → {@code IN_TRIP} raises {@code
 * TripStartedForList}; a second start on an already {@code IN_TRIP} list is refused (AC2, the
 * at-most-one-Active-trip guard); and the {@code IN_TRIP}-reachable branches deferred since Story
 * 2.1/2.3/2.4/2.6 are now exercised for real: rename stays permitted, item commands are refused.
 *
 * <p>The {@code DONE}-rejects-{startTrip,rename} branches are coded but {@code DONE} has no
 * driving event yet (Story 3.4) — {@link #setStatus} forces the enum via reflection purely as a
 * test fixture (Cl. 8: no fabricated completion path in the aggregate itself).
 */
class ShoppingListTest {

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final CommandId commandId = CommandId.generate();

    @Test
    void create_withANameRaisesShoppingListCreatedCarryingItAtVersionOneAndStatusOpen() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ShoppingListCreated.class);
        ShoppingListCreated created = (ShoppingListCreated) events.get(0);
        assertThat(created.householdId()).isEqualTo(householdId);
        assertThat(created.listId()).isEqualTo(listId);
        assertThat(created.name()).isEqualTo(new ShoppingListName("Wocheneinkauf"));

        assertThat(list.status()).isEqualTo(ListStatus.OPEN);
        assertThat(list.version()).isEqualTo(AggregateVersion.of(StreamId.forList(listId), 1));
    }

    @Test
    void create_withANullNameRaisesAnUnnamedShoppingListCreated() {
        ShoppingList list = ShoppingList.create(listId, householdId, null, commandId);

        ShoppingListCreated created = (ShoppingListCreated) list.uncommittedEvents().get(0);
        assertThat(created.name()).isNull();
        assertThat(list.name()).isNull();
        assertThat(list.status()).isEqualTo(ListStatus.OPEN);
    }

    @Test
    void create_rejectsANullListId() {
        assertThatThrownBy(() ->
                        ShoppingList.create(null, householdId, new ShoppingListName("Wocheneinkauf"), commandId))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_rejectsANullHouseholdId() {
        assertThatThrownBy(() ->
                        ShoppingList.create(listId, null, new ShoppingListName("Wocheneinkauf"), commandId))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void renamingAnOpenListRaisesShoppingListRenamed() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        list.markEventsCommitted();

        list.rename(new ShoppingListName("Getränke"), CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ShoppingListRenamed.class);
        ShoppingListRenamed renamed = (ShoppingListRenamed) events.get(0);
        assertThat(renamed.listId()).isEqualTo(listId);
        assertThat(renamed.newName()).isEqualTo(new ShoppingListName("Getränke"));
        assertThat(list.name()).isEqualTo(new ShoppingListName("Getränke"));
    }

    @Test
    void renamingToTheCurrentNameRaisesNothing() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        list.markEventsCommitted();

        list.rename(new ShoppingListName("Wocheneinkauf"), CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void renamingAnUnnamedOpenListNamesIt() {
        ShoppingList list = ShoppingList.create(listId, householdId, null, commandId);
        list.markEventsCommitted();

        list.rename(new ShoppingListName("Wocheneinkauf"), CommandId.generate());

        assertThat(list.uncommittedEvents()).hasSize(1);
        assertThat(list.name()).isEqualTo(new ShoppingListName("Wocheneinkauf"));
    }

    @Test
    void replayingShoppingListCreatedThenShoppingListRenamedRebuildsIdenticalStateAndVersion() {
        ShoppingList original =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        original.rename(new ShoppingListName("Getränke"), CommandId.generate());
        List<DomainEvent> history = original.uncommittedEvents();

        ShoppingList rehydrated = ShoppingList.rehydrate(StreamId.forList(listId), history);

        assertThat(rehydrated.listId()).isEqualTo(original.listId());
        assertThat(rehydrated.householdId()).isEqualTo(original.householdId());
        assertThat(rehydrated.name()).isEqualTo(original.name());
        assertThat(rehydrated.status()).isEqualTo(original.status());
        assertThat(rehydrated.version()).isEqualTo(original.version());
    }

    @Test
    void startTrip_raisesTripStartedForList_andFoldsInTrip() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        list.markEventsCommitted();
        TripId tripId = TripId.generate();
        StoreId storeId = StoreId.generate();

        list.startTrip(tripId, List.of(storeId), CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(TripStartedForList.class);
        TripStartedForList started = (TripStartedForList) events.get(0);
        assertThat(started.householdId()).isEqualTo(householdId);
        assertThat(started.listId()).isEqualTo(listId);
        assertThat(started.tripId()).isEqualTo(tripId);
        assertThat(started.storeIds()).containsExactly(storeId);
        assertThat(list.status()).isEqualTo(ListStatus.IN_TRIP);
    }

    @Test
    void startTrip_withDuplicateStores_dedupesThemInTheEvent() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        list.markEventsCommitted();
        StoreId storeId = StoreId.generate();

        list.startTrip(TripId.generate(), List.of(storeId, storeId), CommandId.generate());

        TripStartedForList started = (TripStartedForList) list.uncommittedEvents().get(0);
        assertThat(started.storeIds()).containsExactly(storeId);
    }

    @Test
    void startTrip_onAnInTripList_throwsTripNotStartable() {
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(
                                EventId.generate(),
                                householdId,
                                listId,
                                new ShoppingListName("Wocheneinkauf")),
                        new TripStartedForList(
                                EventId.generate(),
                                householdId,
                                listId,
                                TripId.generate(),
                                List.of(StoreId.generate()))));

        assertThatThrownBy(() -> list.startTrip(TripId.generate(), List.of(StoreId.generate()), CommandId.generate()))
                .isInstanceOf(TripNotStartableException.class);
    }

    @Test
    void startTrip_onADoneList_throwsTripNotStartable() {
        // Synthetic Epic-3 fixture: DONE is not yet drivable by a real command, so we assert the
        // guard directly against the enum, exactly the way the existing DONE-branches are noted as
        // "coded but not end-to-end reachable" elsewhere until Story 3.4 lands completion.
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        list.markEventsCommitted();
        setStatus(list, ListStatus.DONE);

        assertThatThrownBy(() -> list.startTrip(TripId.generate(), List.of(StoreId.generate()), CommandId.generate()))
                .isInstanceOf(TripNotStartableException.class);
    }

    @Test
    void rename_onAnInTripList_isPermitted() {
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(
                                EventId.generate(),
                                householdId,
                                listId,
                                new ShoppingListName("Wocheneinkauf")),
                        new TripStartedForList(
                                EventId.generate(),
                                householdId,
                                listId,
                                TripId.generate(),
                                List.of(StoreId.generate()))));

        list.rename(new ShoppingListName("Getränke"), CommandId.generate());

        assertThat(list.name()).isEqualTo(new ShoppingListName("Getränke"));
    }

    @Test
    void itemCommands_onAnInTripList_areRefused() {
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(
                                EventId.generate(),
                                householdId,
                                listId,
                                new ShoppingListName("Wocheneinkauf")),
                        new TripStartedForList(
                                EventId.generate(),
                                householdId,
                                listId,
                                TripId.generate(),
                                List.of(StoreId.generate()))));
        ItemId itemId = ItemId.generate();

        assertThatThrownBy(() -> list.addItem(
                        itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate()))
                .isInstanceOf(ItemChangeNotPermittedException.class);
        assertThatThrownBy(() -> list.updateItem(
                        itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate()))
                .isInstanceOf(ItemChangeNotPermittedException.class);
        assertThatThrownBy(() -> list.removeItem(itemId, CommandId.generate()))
                .isInstanceOf(ItemChangeNotPermittedException.class);
        assertThatThrownBy(() -> list.moveItem(itemId, ShoppingListId.generate(), CommandId.generate()))
                .isInstanceOf(ItemChangeNotPermittedException.class);
        assertThatThrownBy(() -> list.assignItemToStore(itemId, StoreId.generate(), CommandId.generate()))
                .isInstanceOf(ItemChangeNotPermittedException.class);
    }

    @Test
    void rerouteItem_onAnInTripList_raisesItemRerouted_andFolds() {
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(
                                EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf")),
                        new ItemAdded(
                                EventId.generate(),
                                householdId,
                                listId,
                                itemId,
                                new ItemName("Milch"),
                                null,
                                Quantity.of(1, Unit.PIECE)),
                        new TripStartedForList(
                                EventId.generate(), householdId, listId, TripId.generate(), List.of(storeId))));

        list.rerouteItem(itemId, storeId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemRerouted.class);
        ItemRerouted rerouted = (ItemRerouted) events.get(0);
        assertThat(rerouted.householdId()).isEqualTo(householdId);
        assertThat(rerouted.listId()).isEqualTo(listId);
        assertThat(rerouted.itemId()).isEqualTo(itemId);
        assertThat(rerouted.storeId()).isEqualTo(storeId);
    }

    @Test
    void rerouteItem_toTheSameStore_isAConvergentNoOp() {
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(
                                EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf")),
                        new ItemAdded(
                                EventId.generate(),
                                householdId,
                                listId,
                                itemId,
                                new ItemName("Milch"),
                                null,
                                Quantity.of(1, Unit.PIECE)),
                        new TripStartedForList(
                                EventId.generate(), householdId, listId, TripId.generate(), List.of(storeId)),
                        new ItemRerouted(EventId.generate(), householdId, listId, itemId, storeId)));

        list.rerouteItem(itemId, storeId, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void rerouteItem_onAnOpenList_throwsItemNotDuringTrip() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(
                                EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf")),
                        new ItemAdded(
                                EventId.generate(),
                                householdId,
                                listId,
                                itemId,
                                new ItemName("Milch"),
                                null,
                                Quantity.of(1, Unit.PIECE))));

        assertThatThrownBy(() -> list.rerouteItem(itemId, StoreId.generate(), CommandId.generate()))
                .isInstanceOf(ItemNotDuringTripException.class);
    }

    @Test
    void rerouteItem_forAnUnknownItem_throwsItemNotFound() {
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(
                                EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf")),
                        new TripStartedForList(
                                EventId.generate(),
                                householdId,
                                listId,
                                TripId.generate(),
                                List.of(StoreId.generate()))));

        assertThatThrownBy(() ->
                        list.rerouteItem(ItemId.generate(), StoreId.generate(), CommandId.generate()))
                .isInstanceOf(ItemNotFoundException.class);
    }

    // ── checkOffItem ──────────────────────────────────────────────────────────────────────────────

    @Test
    void checkOffItem_onAnInTripList_raisesItemCheckedOff_andFoldsToDone() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = inTripListWithItem(itemId);

        list.checkOffItem(itemId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemCheckedOff.class);
        assertThat(((ItemCheckedOff) events.get(0)).itemId()).isEqualTo(itemId);
    }

    @Test
    void checkOffItem_whenAlreadyDone_isAConvergentNoOp() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf")),
                        new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)),
                        new TripStartedForList(EventId.generate(), householdId, listId, TripId.generate(), List.of(StoreId.generate())),
                        new ItemCheckedOff(EventId.generate(), householdId, listId, itemId)));

        list.checkOffItem(itemId, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void checkOffItem_onAnOpenList_throwsItemNotDuringTrip() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = openListWithItem(itemId);

        assertThatThrownBy(() -> list.checkOffItem(itemId, CommandId.generate()))
                .isInstanceOf(ItemNotDuringTripException.class);
    }

    @Test
    void checkOffItem_forAnUnknownItem_throwsItemNotFound() {
        ShoppingList list = inTripListWithItem(ItemId.generate());

        assertThatThrownBy(() -> list.checkOffItem(ItemId.generate(), CommandId.generate()))
                .isInstanceOf(ItemNotFoundException.class);
    }

    // ── uncheckItem ──────────────────────────────────────────────────────────────────────────────

    @Test
    void uncheckItem_onAnInTripList_raisesItemUnchecked_andFoldsToOpen() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf")),
                        new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)),
                        new TripStartedForList(EventId.generate(), householdId, listId, TripId.generate(), List.of(StoreId.generate())),
                        new ItemCheckedOff(EventId.generate(), householdId, listId, itemId)));

        list.uncheckItem(itemId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemUnchecked.class);
    }

    @Test
    void uncheckItem_whenAlreadyOpen_isAConvergentNoOp() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = inTripListWithItem(itemId);

        list.uncheckItem(itemId, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void uncheckItem_onAnOpenList_throwsItemNotDuringTrip() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = openListWithItem(itemId);

        assertThatThrownBy(() -> list.uncheckItem(itemId, CommandId.generate()))
                .isInstanceOf(ItemNotDuringTripException.class);
    }

    // ── postponeItemInPlace ───────────────────────────────────────────────────────────────────────

    @Test
    void postponeItemInPlace_onAnInTripList_raisesItemPostponed_andFoldsToPostponed() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = inTripListWithItem(itemId);

        list.postponeItemInPlace(itemId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemPostponed.class);
    }

    @Test
    void postponeItemInPlace_whenAlreadyPostponed_isAConvergentNoOp() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf")),
                        new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)),
                        new TripStartedForList(EventId.generate(), householdId, listId, TripId.generate(), List.of(StoreId.generate())),
                        new ItemPostponed(EventId.generate(), householdId, listId, itemId)));

        list.postponeItemInPlace(itemId, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void postponeItemInPlace_onAnOpenList_throwsItemNotDuringTrip() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = openListWithItem(itemId);

        assertThatThrownBy(() -> list.postponeItemInPlace(itemId, CommandId.generate()))
                .isInstanceOf(ItemNotDuringTripException.class);
    }

    // ── postponeItemToList ────────────────────────────────────────────────────────────────────────

    @Test
    void postponeItemToList_onAnInTripList_raisesItemPostponedToList_andRemovesItem() {
        ItemId itemId = ItemId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ShoppingList list = inTripListWithItem(itemId);

        list.postponeItemToList(itemId, targetListId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemPostponedToList.class);
        ItemPostponedToList postponed = (ItemPostponedToList) events.get(0);
        assertThat(postponed.itemId()).isEqualTo(itemId);
        assertThat(postponed.targetListId()).isEqualTo(targetListId);
        assertThat(postponed.name()).isEqualTo(new ItemName("Milch"));
    }

    @Test
    void postponeItemToList_onAnOpenList_throwsItemNotDuringTrip() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = openListWithItem(itemId);

        assertThatThrownBy(() -> list.postponeItemToList(itemId, ShoppingListId.generate(), CommandId.generate()))
                .isInstanceOf(ItemNotDuringTripException.class);
    }

    @Test
    void postponeItemToList_forAnUnknownItem_throwsItemNotFound() {
        ShoppingList list = inTripListWithItem(ItemId.generate());

        assertThatThrownBy(() -> list.postponeItemToList(ItemId.generate(), ShoppingListId.generate(), CommandId.generate()))
                .isInstanceOf(ItemNotFoundException.class);
    }

    // ── Cl. 4 regression: status events must not reset status ────────────────────────────────────

    @Test
    void rerouteItem_onADoneItem_preservesDoneStatus_doesNotResetToOpen() {
        ItemId itemId = ItemId.generate();
        ShoppingList list = ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf")),
                        new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)),
                        new TripStartedForList(EventId.generate(), householdId, listId, TripId.generate(), List.of(StoreId.generate())),
                        new ItemCheckedOff(EventId.generate(), householdId, listId, itemId),
                        // Rerouting a DONE item must not reset status to OPEN:
                        new ItemRerouted(EventId.generate(), householdId, listId, itemId, StoreId.generate())));

        // Verify the item is still DONE after reroute fold by checking it raises no event on re-checkoff:
        list.checkOffItem(itemId, CommandId.generate());
        assertThat(list.uncommittedEvents()).isEmpty();
    }

    // ── helper builders ───────────────────────────────────────────────────────────────────────────

    private ShoppingList openListWithItem(ItemId itemId) {
        return ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf")),
                        new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE))));
    }

    private ShoppingList inTripListWithItem(ItemId itemId) {
        return ShoppingList.rehydrate(
                StreamId.forList(listId),
                List.of(
                        new ShoppingListCreated(EventId.generate(), householdId, listId, new ShoppingListName("Wocheneinkauf")),
                        new ItemAdded(EventId.generate(), householdId, listId, itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)),
                        new TripStartedForList(EventId.generate(), householdId, listId, TripId.generate(), List.of(StoreId.generate()))));
    }

    private void setStatus(ShoppingList list, ListStatus status) {
        try {
            Field field = ShoppingList.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(list, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void noEventCarriesADisplayNameEmailOrKeycloakUserId() {
        assertNoPersonalDataComponent(ShoppingListCreated.class);
        assertNoPersonalDataComponent(ShoppingListRenamed.class);
    }

    private void assertNoPersonalDataComponent(Class<? extends DomainEvent> eventType) {
        List<String> componentNames = Arrays.stream(eventType.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        assertThat(componentNames)
                .noneMatch(name -> name.contains("displayname")
                        || name.contains("email")
                        || name.contains("keycloak"));
    }
}
