package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemMovedToList;
import de.sgart.collaboration.domain.event.ItemPostponedToList;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import de.sgart.shared.Unit;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} only, no framework or persistence (CLAUDE.md §6).
 * Proves SGART's first process manager (Story 2.4, AD-10): a triggering {@code ItemMovedToList}
 * appends {@code ItemAdded} to the target under the derived command id, re-processing the same
 * event is exactly-once (idempotent), a target already holding the item's key converges silently
 * (the race safety net for a stale client pre-check, Cl. 3), and a non-Open target is a defensive
 * no-op.
 */
class ItemMoveProcessManagerTest {

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final ItemMoveProcessManager processManager = new ItemMoveProcessManager(eventStore);

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId sourceListId = ShoppingListId.generate();
    private final ShoppingListId targetListId = ShoppingListId.generate();
    private final StreamId targetStreamId = StreamId.forList(targetListId);

    private void seedTargetList() {
        ShoppingList target =
                ShoppingList.create(targetListId, householdId, new ShoppingListName("Getränke"), CommandId.generate());
        eventStore.append(AggregateVersion.initial(targetStreamId), target.uncommittedEvents(), CommandId.generate());
    }

    private ItemMovedToList movedEvent() {
        return new ItemMovedToList(
                EventId.generate(),
                householdId,
                sourceListId,
                ItemId.generate(),
                targetListId,
                new ItemName("Milch"),
                new ItemNote("Bio"),
                Quantity.of(2, Unit.PIECE));
    }

    @Test
    void appendsItemAddedToTheTargetUnderTheDerivedCommandId() {
        seedTargetList();
        ItemMovedToList moved = movedEvent();

        processManager.onItemMovedToList(moved);

        List<DomainEvent> targetEvents = eventStore.readStream(targetStreamId);
        assertThat(targetEvents).hasSize(2);
        assertThat(targetEvents.get(1)).isInstanceOf(ItemAdded.class);
        ItemAdded added = (ItemAdded) targetEvents.get(1);
        assertThat(added.itemId()).isEqualTo(moved.itemId());
        assertThat(added.name()).isEqualTo(moved.name());
        assertThat(added.note()).isEqualTo(moved.note());
        assertThat(added.quantity()).isEqualTo(moved.quantity());
    }

    @Test
    void processingTheSameEventTwiceAppendsOnlyOnce() {
        seedTargetList();
        ItemMovedToList moved = movedEvent();

        processManager.onItemMovedToList(moved);
        processManager.onItemMovedToList(moved);

        assertThat(eventStore.readStream(targetStreamId)).hasSize(2); // create + exactly one ItemAdded
    }

    @Test
    void replayingTheMoveAfterTheTargetItemWasRemovedDoesNotReAddIt() {
        // The exactly-once mechanism the story names (Task 6 / AC2): the derived command id, not the
        // DuplicateItemException swallow. The swallow only masks the item-still-present case; here the
        // moved item is added, then removed from the target, so a replay finds the target WITHOUT the
        // item — only CommandId.deterministicFrom(eventId) keeps the re-append a no-op.
        seedTargetList();
        ItemMovedToList moved = movedEvent();

        processManager.onItemMovedToList(moved);

        ShoppingList target = ShoppingList.rehydrate(targetStreamId, eventStore.readStream(targetStreamId));
        AggregateVersion afterAdd = target.version();
        target.removeItem(moved.itemId(), CommandId.generate());
        eventStore.append(afterAdd, target.uncommittedEvents(), CommandId.generate());

        // Redelivery of the same move event on a subscription restart / catch-up replay.
        processManager.onItemMovedToList(moved);

        List<DomainEvent> targetEvents = eventStore.readStream(targetStreamId);
        assertThat(targetEvents).hasSize(3); // create + ItemAdded + ItemRemoved — no second ItemAdded
        assertThat(targetEvents.stream().filter(event -> event instanceof ItemAdded)).hasSize(1);
    }

    @Test
    void aTargetAlreadyHoldingTheItemsKeyConvergesWithoutAppendingOrThrowing() {
        seedTargetList();
        ItemMovedToList moved = movedEvent();
        // Pre-existing item on the target with the same (name, note) key — the rare race a stale
        // client pre-check could produce (Cl. 3).
        ShoppingList target = ShoppingList.rehydrate(targetStreamId, eventStore.readStream(targetStreamId));
        target.addItem(ItemId.generate(), moved.name(), moved.note(), Quantity.of(1, Unit.PIECE), CommandId.generate());
        eventStore.append(AggregateVersion.initial(targetStreamId).next(), target.uncommittedEvents(), CommandId.generate());

        processManager.onItemMovedToList(moved);

        assertThat(eventStore.readStream(targetStreamId)).hasSize(2); // create + the pre-existing add only
    }

    @Test
    void retriesTheTargetAppendWhenAConcurrentWriteAdvancesTheTargetStream() {
        // A store that rejects the first append with a conflict, then delegates — simulating a
        // concurrent write landing on the target between the PM's read and its append. Without the
        // retry the moved item would be stranded on neither list (data loss).
        InMemoryEventStore delegate = new InMemoryEventStore();
        ShoppingList target =
                ShoppingList.create(targetListId, householdId, new ShoppingListName("Getränke"), CommandId.generate());
        delegate.append(AggregateVersion.initial(targetStreamId), target.uncommittedEvents(), CommandId.generate());

        AtomicBoolean conflictOnce = new AtomicBoolean(true);
        EventStore flakyStore = new EventStore() {
            @Override
            public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
                if (conflictOnce.getAndSet(false)) {
                    throw new ConcurrencyConflictException(expectedVersion, expectedVersion.next());
                }
                delegate.append(expectedVersion, events, commandId);
            }

            @Override
            public List<DomainEvent> readStream(StreamId streamId) {
                return delegate.readStream(streamId);
            }
        };
        ItemMoveProcessManager retryingManager = new ItemMoveProcessManager(flakyStore);

        retryingManager.onItemMovedToList(movedEvent());

        List<DomainEvent> targetEvents = delegate.readStream(targetStreamId);
        assertThat(targetEvents).hasSize(2); // create + exactly one ItemAdded, appended on the retry
        assertThat(targetEvents.get(1)).isInstanceOf(ItemAdded.class);
    }

    @Test
    void aVanishedTargetStreamIsSkippedWithoutThrowing() {
        // No seedTargetList() — the target stream has never been created (Epic-2-unreachable,
        // defensive: no Epic-2 event ever deletes a list stream).
        ItemMovedToList moved = movedEvent();

        processManager.onItemMovedToList(moved);

        assertThat(eventStore.readStream(targetStreamId)).isEmpty();
    }

    // --- Story 3.3: the postpone-to-list reaction shares the same add-to-target machinery ---

    private ItemPostponedToList postponedEvent() {
        return new ItemPostponedToList(
                EventId.generate(),
                householdId,
                sourceListId,
                ItemId.generate(),
                targetListId,
                new ItemName("Milch"),
                new ItemNote("Bio"),
                Quantity.of(2, Unit.PIECE));
    }

    @Test
    void appendsItemAddedToTheTargetForAPostponeToList() {
        seedTargetList();
        ItemPostponedToList postponed = postponedEvent();

        processManager.onItemPostponedToList(postponed);

        List<DomainEvent> targetEvents = eventStore.readStream(targetStreamId);
        assertThat(targetEvents).hasSize(2);
        assertThat(targetEvents.get(1)).isInstanceOf(ItemAdded.class);
        ItemAdded added = (ItemAdded) targetEvents.get(1);
        assertThat(added.itemId()).isEqualTo(postponed.itemId());
        assertThat(added.name()).isEqualTo(postponed.name());
        assertThat(added.note()).isEqualTo(postponed.note());
        assertThat(added.quantity()).isEqualTo(postponed.quantity());
    }

    @Test
    void processingTheSamePostponeTwiceAppendsOnlyOnce() {
        seedTargetList();
        ItemPostponedToList postponed = postponedEvent();

        processManager.onItemPostponedToList(postponed);
        processManager.onItemPostponedToList(postponed);

        assertThat(eventStore.readStream(targetStreamId)).hasSize(2); // create + exactly one ItemAdded
    }

    @Test
    void aTargetThatLeftOpenDropsThePostponeWithoutAppendingOrThrowing() {
        // D2 interim guard (story 3-6): the target starts a trip (→ IN_TRIP) between the handler's
        // OPEN check and this async add, so target.addItem raises ItemChangeNotPermittedException.
        // The PM must not throw — it logs the unrecoverable-transfer condition and drops, appending
        // nothing to the target (the auto-recovering two-phase saga is deferred to story 3-6).
        seedTargetList();
        ShoppingList target = ShoppingList.rehydrate(targetStreamId, eventStore.readStream(targetStreamId));
        target.startTrip(TripId.generate(), List.of(StoreId.generate()), CommandId.generate());
        eventStore.append(AggregateVersion.initial(targetStreamId).next(), target.uncommittedEvents(), CommandId.generate());
        int sizeBefore = eventStore.readStream(targetStreamId).size();

        processManager.onItemPostponedToList(postponedEvent());

        // create + TripStartedForList only — no ItemAdded, and no exception escaped.
        assertThat(eventStore.readStream(targetStreamId)).hasSize(sizeBefore);
        assertThat(eventStore.readStream(targetStreamId).stream().filter(event -> event instanceof ItemAdded)).isEmpty();
    }
}
