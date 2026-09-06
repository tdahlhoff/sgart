package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemAssignedToStore;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemTransferCancelled;
import de.sgart.collaboration.domain.event.ItemTransferConfirmed;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.exception.DuplicateItemException;
import de.sgart.collaboration.domain.exception.ItemNotFoundException;
import de.sgart.collaboration.domain.exception.ItemTransferInProgressException;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import de.sgart.shared.Unit;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * {@code Item} entity inside {@link ShoppingList} (Story 2.3, AD-10): add/update/remove raise the
 * right events, the (name, note) dedup key rejects exact duplicates and colliding updates,
 * unchanged updates and unknown removals are convergent no-ops, and replay rebuilds identical
 * state.
 *
 * <p>The {@code DONE}-rejects-item branch (AC5) is coded ({@link ShoppingList} guards every item
 * command with the same {@code requireOpen()} the aggregate uses internally) but only reachable
 * end-to-end once Epic 3 introduces a status-changing transition beyond {@code ShoppingListCreated}
 * — see Story 2.1 Clarification 1 and {@code deferred-work.md}; no synthetic Epic-3 event exists to
 * drive the aggregate into {@code DONE}, so it is not exercised here. {@link ShoppingList#moveItem}
 * (Story 2.4, AD-10 — the source side of the move) and {@link ShoppingList#assignItemToStore}
 * (Story 2.6, AC5) reuse the identical guard, so their {@code DONE} branches are deferred for the
 * same reason.
 *
 * <p>Story 3.6 reshaped {@link ShoppingList#moveItem} into the two-phase reserve-then-remove
 * transfer saga: it now raises {@code ItemTransferInitiated} and the item stays reserved on this
 * list (no longer removed here) until {@code confirmItemTransfer}/{@code cancelItemTransfer}
 * resolves it — also covered below, alongside the fail-fast lock on a reserved item.
 */
class ShoppingListItemsTest {

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final CommandId commandId = CommandId.generate();

    private ShoppingList openList() {
        ShoppingList list = ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        list.markEventsCommitted();
        return list;
    }

    @Test
    void addingAnItemRaisesItemAdded() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();

        list.addItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemAdded.class);
        ItemAdded added = (ItemAdded) events.get(0);
        assertThat(added.householdId()).isEqualTo(householdId);
        assertThat(added.listId()).isEqualTo(listId);
        assertThat(added.itemId()).isEqualTo(itemId);
        assertThat(added.name()).isEqualTo(new ItemName("Milch"));
        assertThat(added.note()).isEqualTo(new ItemNote("Bio"));
        assertThat(added.quantity()).isEqualTo(Quantity.of(1, Unit.PIECE));
    }

    @Test
    void addingAnItemWithNoNoteRaisesItemAddedWithANullNote() {
        ShoppingList list = openList();

        list.addItem(ItemId.generate(), new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());

        ItemAdded added = (ItemAdded) list.uncommittedEvents().get(0);
        assertThat(added.note()).isNull();
    }

    @Test
    void addingAnExactDuplicateNameAndNoteIsRejected() {
        ShoppingList list = openList();
        list.addItem(ItemId.generate(), new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        assertThatThrownBy(() -> list.addItem(
                        ItemId.generate(), new ItemName("milch"), new ItemNote(" bio "), Quantity.of(2, Unit.PIECE), CommandId.generate()))
                .isInstanceOf(DuplicateItemException.class);
    }

    @Test
    void sameNameWithDifferentNoteCoexists() {
        ShoppingList list = openList();
        list.addItem(ItemId.generate(), new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.addItem(ItemId.generate(), new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());

        assertThat(list.uncommittedEvents()).hasSize(1);
    }

    @Test
    void updatingAnItemRaisesItemUpdated() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.updateItem(itemId, new ItemName("Milch"), new ItemNote("Bio 1,5%"), Quantity.of(2, Unit.PIECE), CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemUpdated.class);
        ItemUpdated updated = (ItemUpdated) events.get(0);
        assertThat(updated.itemId()).isEqualTo(itemId);
        assertThat(updated.note()).isEqualTo(new ItemNote("Bio 1,5%"));
        assertThat(updated.quantity()).isEqualTo(Quantity.of(2, Unit.PIECE));
    }

    @Test
    void updatingAnItemToItsCurrentValuesRaisesNothing() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.updateItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void updatingAnItemWithTheSameQuantityValueButADifferentScaleRaisesNothing() {
        // Regression: the convergent-no-op check compares the amount by value (compareTo), not
        // BigDecimal.equals (scale-sensitive: 1 != 1.0) — re-sending "1.0" for a stored "1" with an
        // otherwise unchanged item must stay a no-op, not emit a spurious ItemUpdated (AD-8).
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.updateItem(
                itemId,
                new ItemName("Milch"),
                new ItemNote("Bio"),
                new Quantity(new BigDecimal("1.0"), Unit.PIECE),
                CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void updatingAMissingItemIsNotFound() {
        ShoppingList list = openList();

        assertThatThrownBy(() -> list.updateItem(
                        ItemId.generate(), new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate()))
                .isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    void updatingAnItemToCollideWithADifferentItemIsRejected() {
        ShoppingList list = openList();
        ItemId milchId = ItemId.generate();
        ItemId brotId = ItemId.generate();
        list.addItem(milchId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.addItem(brotId, new ItemName("Brot"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        assertThatThrownBy(() -> list.updateItem(
                        brotId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate()))
                .isInstanceOf(DuplicateItemException.class);
    }

    @Test
    void updatingAnItemToItsOwnUnchangedKeyIsAllowed() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.updateItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(3, Unit.PIECE), CommandId.generate());

        assertThat(list.uncommittedEvents()).hasSize(1);
    }

    @Test
    void removingAnItemRaisesItemRemoved() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.removeItem(itemId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemRemoved.class);
        assertThat(((ItemRemoved) events.get(0)).itemId()).isEqualTo(itemId);
    }

    @Test
    void removingAnUnknownItemRaisesNothing() {
        ShoppingList list = openList();

        list.removeItem(ItemId.generate(), CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void removingAnAlreadyRemovedItemRaisesNothing() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();
        list.removeItem(itemId, CommandId.generate());
        list.markEventsCommitted();

        list.removeItem(itemId, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void movingAnItemRaisesItemTransferInitiatedCarryingTheItemsCurrentFields() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        list.addItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.moveItem(itemId, targetListId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemTransferInitiated.class);
        ItemTransferInitiated initiated = (ItemTransferInitiated) events.get(0);
        assertThat(initiated.householdId()).isEqualTo(householdId);
        assertThat(initiated.sourceListId()).isEqualTo(listId);
        assertThat(initiated.itemId()).isEqualTo(itemId);
        assertThat(initiated.targetListId()).isEqualTo(targetListId);
        assertThat(initiated.name()).isEqualTo(new ItemName("Milch"));
        assertThat(initiated.note()).isEqualTo(new ItemNote("Bio"));
        assertThat(initiated.quantity()).isEqualTo(Quantity.of(2, Unit.PIECE));
        assertThat(initiated.origin()).isEqualTo(TransferOrigin.PLANNING_MOVE);
    }

    @Test
    void movingAnItemKeepsItReservedInTheSourcesFoldedState() {
        // Story 3.6, AC1 — the reshaped moveItem reserves instead of removing.
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.moveItem(itemId, ShoppingListId.generate(), CommandId.generate());

        // The item is still present in the source's own folded state — re-adding its exact key is
        // still rejected as a duplicate (it was reserved, not removed).
        assertThatThrownBy(() -> list.addItem(
                        ItemId.generate(), new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate()))
                .isInstanceOf(DuplicateItemException.class);
    }

    @Test
    void movingAnUnknownItemIsNotFound() {
        ShoppingList list = openList();

        assertThatThrownBy(() -> list.moveItem(ItemId.generate(), ShoppingListId.generate(), CommandId.generate()))
                .isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    void movingAReservedItemToADifferentTargetThrowsItemTransferInProgress() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.moveItem(itemId, ShoppingListId.generate(), CommandId.generate());

        assertThatThrownBy(() -> list.moveItem(itemId, ShoppingListId.generate(), CommandId.generate()))
                .isInstanceOf(ItemTransferInProgressException.class);
    }

    @Test
    void movingAReservedItemToTheSameTargetIsAConvergentNoOp() {
        // The lost-response retry (Story 3.6, AC4) — the same target as the in-flight reservation.
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.moveItem(itemId, targetListId, CommandId.generate());
        list.markEventsCommitted();

        list.moveItem(itemId, targetListId, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void updatingAReservedItemThrowsItemTransferInProgress() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.moveItem(itemId, ShoppingListId.generate(), CommandId.generate());

        assertThatThrownBy(() -> list.updateItem(
                        itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.PIECE), CommandId.generate()))
                .isInstanceOf(ItemTransferInProgressException.class);
    }

    @Test
    void removingAReservedItemThrowsItemTransferInProgress() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.moveItem(itemId, ShoppingListId.generate(), CommandId.generate());

        assertThatThrownBy(() -> list.removeItem(itemId, CommandId.generate()))
                .isInstanceOf(ItemTransferInProgressException.class);
    }

    @Test
    void assigningAReservedItemToAStoreThrowsItemTransferInProgress() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.moveItem(itemId, ShoppingListId.generate(), CommandId.generate());

        assertThatThrownBy(() -> list.assignItemToStore(itemId, StoreId.generate(), CommandId.generate()))
                .isInstanceOf(ItemTransferInProgressException.class);
    }

    // The IN_TRIP-only mutations (checkOffItem/uncheckItem/discardItem/rerouteItem/postponeItemToList)
    // and their fail-fast-lock coverage live in ShoppingListTest, which already builds IN_TRIP fixtures.

    @Test
    void confirmingATransferRaisesItemTransferConfirmedAndRemovesTheItem() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.moveItem(itemId, ShoppingListId.generate(), CommandId.generate());
        list.markEventsCommitted();

        list.confirmItemTransfer(itemId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemTransferConfirmed.class);
        ItemTransferConfirmed confirmed = (ItemTransferConfirmed) events.get(0);
        assertThat(confirmed.listId()).isEqualTo(listId);
        assertThat(confirmed.itemId()).isEqualTo(itemId);
        // Truly gone now — re-adding its key succeeds.
        assertThatCode(() -> list.addItem(
                        ItemId.generate(), new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate()))
                .doesNotThrowAnyException();
    }

    @Test
    void cancellingATransferRaisesItemTransferCancelledAndUnReservesTheItem() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.moveItem(itemId, ShoppingListId.generate(), CommandId.generate());
        list.markEventsCommitted();

        list.cancelItemTransfer(itemId, TransferCancellationReason.TARGET_GONE, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemTransferCancelled.class);
        ItemTransferCancelled cancelled = (ItemTransferCancelled) events.get(0);
        assertThat(cancelled.listId()).isEqualTo(listId);
        assertThat(cancelled.itemId()).isEqualTo(itemId);
        assertThat(cancelled.reason()).isEqualTo(TransferCancellationReason.TARGET_GONE);
        list.markEventsCommitted();
        // Un-reserved — the item is mutable again, the lock is gone.
        assertThatCode(() -> list.updateItem(
                        itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.PIECE), CommandId.generate()))
                .doesNotThrowAnyException();
    }

    @Test
    void confirmingATransferForAnAbsentItemIsAConvergentNoOp() {
        ShoppingList list = openList();

        list.confirmItemTransfer(ItemId.generate(), CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void confirmingATransferForAnItemThatIsNotReservedIsAConvergentNoOp() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.confirmItemTransfer(itemId, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void cancellingATransferForAnAbsentItemIsAConvergentNoOp() {
        ShoppingList list = openList();

        list.cancelItemTransfer(ItemId.generate(), TransferCancellationReason.TARGET_GONE, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void cancellingATransferForAnItemThatIsNotReservedIsAConvergentNoOp() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.cancelItemTransfer(itemId, TransferCancellationReason.TARGET_NOT_OPEN, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void confirmingATransferIsNotPhaseGatedAndWorksOnAnInTripList() {
        // Story 3.6, AC2 — a system saga step must resolve regardless of the list's current status.
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.moveItem(itemId, ShoppingListId.generate(), CommandId.generate());
        list.startTrip(TripId.generate(), List.of(StoreId.generate()), CommandId.generate());
        list.markEventsCommitted();

        list.confirmItemTransfer(itemId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemTransferConfirmed.class);
    }

    @Test
    void replayingAConfirmedMoveRebuildsStateWithTheItemTrulyGoneFromTheSource() {
        ItemId itemId = ItemId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ShoppingList original = ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        original.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        original.moveItem(itemId, targetListId, CommandId.generate());
        original.confirmItemTransfer(itemId, CommandId.generate());
        List<DomainEvent> history = original.uncommittedEvents();

        ShoppingList rehydrated = ShoppingList.rehydrate(StreamId.forList(listId), history);

        assertThat(rehydrated.version()).isEqualTo(original.version());
        rehydrated.addItem(ItemId.generate(), new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        assertThat(rehydrated.uncommittedEvents()).hasSize(1);
    }

    @Test
    void replayingACancelledMoveRebuildsStateWithTheItemBackToNormalOnTheSource() {
        ItemId itemId = ItemId.generate();
        ShoppingListId targetListId = ShoppingListId.generate();
        ShoppingList original = ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        original.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        original.moveItem(itemId, targetListId, CommandId.generate());
        original.cancelItemTransfer(itemId, TransferCancellationReason.TARGET_NOT_OPEN, CommandId.generate());
        List<DomainEvent> history = original.uncommittedEvents();

        ShoppingList rehydrated = ShoppingList.rehydrate(StreamId.forList(listId), history);

        assertThat(rehydrated.version()).isEqualTo(original.version());
        // Still present under its old key (un-reserved, not gone) — re-adding it collides.
        assertThatThrownBy(() -> rehydrated.addItem(
                        ItemId.generate(), new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate()))
                .isInstanceOf(DuplicateItemException.class);
        // The lock is lifted — the item can be mutated again.
        assertThatCode(() -> rehydrated.updateItem(
                        itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate()))
                .doesNotThrowAnyException();
    }

    @Test
    void replayingAddUpdateAndRemoveRebuildsIdenticalStateAndVersion() {
        ItemId keptItemId = ItemId.generate();
        ItemId removedItemId = ItemId.generate();
        ShoppingList original = ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        original.addItem(keptItemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate());
        original.addItem(removedItemId, new ItemName("Brot"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        original.updateItem(keptItemId, new ItemName("Milch"), new ItemNote("Bio 1,5%"), Quantity.of(2, Unit.PIECE), CommandId.generate());
        original.removeItem(removedItemId, CommandId.generate());
        List<DomainEvent> history = original.uncommittedEvents();

        ShoppingList rehydrated = ShoppingList.rehydrate(StreamId.forList(listId), history);

        assertThat(rehydrated.version()).isEqualTo(original.version());
        // Re-adding the removed item's key must succeed only if it is really gone from rehydrated state.
        rehydrated.addItem(ItemId.generate(), new ItemName("Brot"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        assertThat(rehydrated.uncommittedEvents()).hasSize(1);
        // Re-adding the kept item's *updated* key must be rejected as a duplicate.
        assertThatThrownBy(() -> rehydrated.addItem(
                        ItemId.generate(), new ItemName("Milch"), new ItemNote("Bio 1,5%"), Quantity.of(1, Unit.PIECE), CommandId.generate()))
                .isInstanceOf(DuplicateItemException.class);
    }

    @Test
    void assigningAnItemToAStoreRaisesItemAssignedToStore() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        list.assignItemToStore(itemId, storeId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ItemAssignedToStore.class);
        ItemAssignedToStore assigned = (ItemAssignedToStore) events.get(0);
        assertThat(assigned.householdId()).isEqualTo(householdId);
        assertThat(assigned.listId()).isEqualTo(listId);
        assertThat(assigned.itemId()).isEqualTo(itemId);
        assertThat(assigned.storeId()).isEqualTo(storeId);
    }

    @Test
    void reassigningAnItemToADifferentStoreRaisesANewEvent() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.assignItemToStore(itemId, StoreId.generate(), CommandId.generate());
        list.markEventsCommitted();
        StoreId otherStoreId = StoreId.generate();

        list.assignItemToStore(itemId, otherStoreId, CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(((ItemAssignedToStore) events.get(0)).storeId()).isEqualTo(otherStoreId);
    }

    @Test
    void assigningAnItemToItsCurrentStoreAgainRaisesNothing() {
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.assignItemToStore(itemId, storeId, CommandId.generate());
        list.markEventsCommitted();

        list.assignItemToStore(itemId, storeId, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void assigningAnUnknownItemToAStoreIsNotFound() {
        ShoppingList list = openList();

        assertThatThrownBy(
                        () -> list.assignItemToStore(ItemId.generate(), StoreId.generate(), CommandId.generate()))
                .isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    void updatingAnAssignedItemPreservesItsStoreAssignment() {
        // Cl. 7 regression trap: apply(ItemUpdated) must carry the folded assignedStore forward.
        ShoppingList list = openList();
        ItemId itemId = ItemId.generate();
        StoreId storeId = StoreId.generate();
        list.addItem(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        list.assignItemToStore(itemId, storeId, CommandId.generate());
        list.updateItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.PIECE), CommandId.generate());
        list.markEventsCommitted();

        // Assigning the SAME store again after the edit must still be a convergent no-op — proving
        // the fold kept the assignment through the ItemUpdated, not wiped it.
        list.assignItemToStore(itemId, storeId, CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void noItemEventCarriesADisplayNameEmailOrKeycloakUserId() {
        assertNoPersonalDataComponent(ItemAdded.class);
        assertNoPersonalDataComponent(ItemUpdated.class);
        assertNoPersonalDataComponent(ItemRemoved.class);
        assertNoPersonalDataComponent(ItemAssignedToStore.class);
        assertNoPersonalDataComponent(ItemTransferInitiated.class);
        assertNoPersonalDataComponent(ItemTransferConfirmed.class);
        assertNoPersonalDataComponent(ItemTransferCancelled.class);
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
