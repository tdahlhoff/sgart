package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.TransferCancellationReason;
import de.sgart.collaboration.domain.TransferOrigin;
import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemTransferCancelled;
import de.sgart.collaboration.domain.event.ItemTransferConfirmed;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.DomainEvent;
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
 * Proves the Story 3.6 two-phase compensating saga: a triggering {@link ItemTransferInitiated}
 * adds the item to the target under the derived command id and then resolves the source with
 * either {@link ItemTransferConfirmed} (target add succeeded, or converged on a pre-existing
 * duplicate) or {@link ItemTransferCancelled} (target not {@code OPEN}, or its stream gone) — the
 * item is never dropped on neither list. Also proves exactly-once replay across both streams and
 * that the process manager does not care which aggregate method (move vs. postpone) raised the
 * triggering event.
 */
class ItemTransferProcessManagerTest {

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final ItemTransferProcessManager processManager = new ItemTransferProcessManager(eventStore);

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId sourceListId = ShoppingListId.generate();
    private final ShoppingListId targetListId = ShoppingListId.generate();
    private final StreamId sourceStreamId = StreamId.forList(sourceListId);
    private final StreamId targetStreamId = StreamId.forList(targetListId);

    private ItemId itemId;

    private void seedTargetList() {
        ShoppingList target =
                ShoppingList.create(targetListId, householdId, new ShoppingListName("Getränke"), CommandId.generate());
        eventStore.append(AggregateVersion.initial(targetStreamId), target.uncommittedEvents(), CommandId.generate());
    }

    /**
     * Builds a real source list holding one item, then calls {@code moveItem} on it so the source
     * stream ends up with a genuine reserved item — the process manager reads this stream back for
     * its confirm/cancel steps, so a hand-built {@link ItemTransferInitiated} disconnected from a
     * real source would not exercise those steps honestly.
     */
    private ItemTransferInitiated initiateMoveOnSource(EventStore store) {
        ShoppingList source =
                ShoppingList.create(sourceListId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        itemId = ItemId.generate();
        source.addItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.PIECE), CommandId.generate());
        store.append(AggregateVersion.initial(sourceStreamId), source.uncommittedEvents(), CommandId.generate());
        source.markEventsCommitted();

        AggregateVersion beforeMove = source.version();
        source.moveItem(itemId, targetListId, CommandId.generate());
        ItemTransferInitiated initiated = (ItemTransferInitiated) source.uncommittedEvents().get(0);
        store.append(beforeMove, source.uncommittedEvents(), CommandId.generate());
        return initiated;
    }

    private ItemTransferInitiated initiateMoveOnSource() {
        return initiateMoveOnSource(eventStore);
    }

    /** Same as {@link #initiateMoveOnSource()} but via the {@code IN_TRIP} postpone phase instead. */
    private ItemTransferInitiated initiatePostponeOnSource() {
        ShoppingList source =
                ShoppingList.create(sourceListId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        itemId = ItemId.generate();
        source.addItem(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.PIECE), CommandId.generate());
        source.startTrip(TripId.generate(), List.of(StoreId.generate()), CommandId.generate());
        eventStore.append(AggregateVersion.initial(sourceStreamId), source.uncommittedEvents(), CommandId.generate());
        source.markEventsCommitted();

        AggregateVersion beforePostpone = source.version();
        source.postponeItemToList(itemId, targetListId, CommandId.generate());
        ItemTransferInitiated initiated = (ItemTransferInitiated) source.uncommittedEvents().get(0);
        eventStore.append(beforePostpone, source.uncommittedEvents(), CommandId.generate());
        return initiated;
    }

    @Test
    void addsItemToTheTargetAndConfirmsTheSourceOnSuccess() {
        seedTargetList();
        ItemTransferInitiated initiated = initiateMoveOnSource();

        processManager.onItemTransferInitiated(initiated);

        List<DomainEvent> targetEvents = eventStore.readStream(targetStreamId);
        assertThat(targetEvents).hasSize(2); // create + ItemAdded
        assertThat(targetEvents.get(1)).isInstanceOf(ItemAdded.class);
        ItemAdded added = (ItemAdded) targetEvents.get(1);
        assertThat(added.itemId()).isEqualTo(initiated.itemId());
        assertThat(added.name()).isEqualTo(initiated.name());
        assertThat(added.note()).isEqualTo(initiated.note());
        assertThat(added.quantity()).isEqualTo(initiated.quantity());

        List<DomainEvent> sourceEvents = eventStore.readStream(sourceStreamId);
        assertThat(sourceEvents).hasSize(4); // create + ItemAdded + ItemTransferInitiated + ItemTransferConfirmed
        assertThat(sourceEvents.get(3)).isInstanceOf(ItemTransferConfirmed.class);

        // The item is truly gone from the source's folded state — re-adding its key must succeed.
        ShoppingList rehydratedSource = ShoppingList.rehydrate(sourceStreamId, sourceEvents);
        assertThatCode(() -> rehydratedSource.addItem(
                        ItemId.generate(), initiated.name(), initiated.note(), initiated.quantity(), CommandId.generate()))
                .doesNotThrowAnyException();
    }

    @Test
    void reprocessingTheSameInitiatedEventIsExactlyOnceOnBothTargetAndSource() {
        seedTargetList();
        ItemTransferInitiated initiated = initiateMoveOnSource();

        processManager.onItemTransferInitiated(initiated);
        // Redelivery of the same event on a subscription restart / catch-up replay.
        processManager.onItemTransferInitiated(initiated);

        assertThat(eventStore.readStream(targetStreamId)).hasSize(2); // create + exactly one ItemAdded
        List<DomainEvent> sourceEvents = eventStore.readStream(sourceStreamId);
        assertThat(sourceEvents).hasSize(4); // create + add + initiated + exactly one confirmed
        assertThat(sourceEvents.stream().filter(event -> event instanceof ItemTransferConfirmed)).hasSize(1);
    }

    @Test
    void cancelsTheSourceWithTargetNotOpenReasonWhenTheTargetLeftOpen() {
        seedTargetList();
        ShoppingList target = ShoppingList.rehydrate(targetStreamId, eventStore.readStream(targetStreamId));
        target.startTrip(TripId.generate(), List.of(StoreId.generate()), CommandId.generate());
        eventStore.append(AggregateVersion.initial(targetStreamId).next(), target.uncommittedEvents(), CommandId.generate());
        int targetSizeBefore = eventStore.readStream(targetStreamId).size();

        ItemTransferInitiated initiated = initiateMoveOnSource();

        processManager.onItemTransferInitiated(initiated);

        // No ItemAdded landed on the target — only the TripStartedForList from setup.
        assertThat(eventStore.readStream(targetStreamId)).hasSize(targetSizeBefore);
        assertThat(eventStore.readStream(targetStreamId).stream().filter(event -> event instanceof ItemAdded)).isEmpty();

        List<DomainEvent> sourceEvents = eventStore.readStream(sourceStreamId);
        assertThat(sourceEvents.get(sourceEvents.size() - 1)).isInstanceOf(ItemTransferCancelled.class);
        assertThat(((ItemTransferCancelled) sourceEvents.get(sourceEvents.size() - 1)).reason())
                .isEqualTo(TransferCancellationReason.TARGET_NOT_OPEN);

        // The item is un-reserved again — the fail-fast lock no longer rejects other mutations on it.
        ShoppingList rehydratedSource = ShoppingList.rehydrate(sourceStreamId, sourceEvents);
        assertThatCode(() -> rehydratedSource.removeItem(itemId, CommandId.generate())).doesNotThrowAnyException();
    }

    @Test
    void cancelsTheSourceWithTargetGoneReasonWhenTheTargetStreamIsMissing() {
        // No seedTargetList() — the target stream has never been created.
        ItemTransferInitiated initiated = initiateMoveOnSource();

        processManager.onItemTransferInitiated(initiated);

        assertThat(eventStore.readStream(targetStreamId)).isEmpty();

        List<DomainEvent> sourceEvents = eventStore.readStream(sourceStreamId);
        assertThat(sourceEvents.get(sourceEvents.size() - 1)).isInstanceOf(ItemTransferCancelled.class);
        assertThat(((ItemTransferCancelled) sourceEvents.get(sourceEvents.size() - 1)).reason())
                .isEqualTo(TransferCancellationReason.TARGET_GONE);
    }

    @Test
    void convergesOnADuplicateTargetKeyByConfirmingTheSourceWithoutASecondAdd() {
        seedTargetList();
        // Pre-existing item on the target with the same (name, note) key — the rare race a stale
        // client pre-check could produce (Story 2.4, Cl. 3).
        ShoppingList target = ShoppingList.rehydrate(targetStreamId, eventStore.readStream(targetStreamId));
        target.addItem(ItemId.generate(), new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), CommandId.generate());
        eventStore.append(AggregateVersion.initial(targetStreamId).next(), target.uncommittedEvents(), CommandId.generate());

        ItemTransferInitiated initiated = initiateMoveOnSource();

        processManager.onItemTransferInitiated(initiated);

        assertThat(eventStore.readStream(targetStreamId)).hasSize(2); // create + the pre-existing add only

        List<DomainEvent> sourceEvents = eventStore.readStream(sourceStreamId);
        assertThat(sourceEvents.get(sourceEvents.size() - 1)).isInstanceOf(ItemTransferConfirmed.class);
    }

    @Test
    void retriesTheTargetAppendWhenAConcurrentWriteAdvancesTheTargetStreamThenConfirms() {
        // A store that rejects the first append with a conflict, then delegates — simulating a
        // concurrent write landing on the target between the PM's read and its append. Without the
        // retry the transferred item would be stranded reserved-but-never-confirmed.
        InMemoryEventStore delegate = new InMemoryEventStore();
        ShoppingList target =
                ShoppingList.create(targetListId, householdId, new ShoppingListName("Getränke"), CommandId.generate());
        delegate.append(AggregateVersion.initial(targetStreamId), target.uncommittedEvents(), CommandId.generate());
        ItemTransferInitiated initiated = initiateMoveOnSource(delegate);

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
        ItemTransferProcessManager retryingManager = new ItemTransferProcessManager(flakyStore);

        retryingManager.onItemTransferInitiated(initiated);

        List<DomainEvent> targetEvents = delegate.readStream(targetStreamId);
        assertThat(targetEvents).hasSize(2); // create + exactly one ItemAdded, appended on the retry
        assertThat(targetEvents.get(1)).isInstanceOf(ItemAdded.class);

        List<DomainEvent> sourceEvents = delegate.readStream(sourceStreamId);
        assertThat(sourceEvents).hasSize(4); // create + add + initiated + confirmed — the conflict was spent on the target
        assertThat(sourceEvents.get(3)).isInstanceOf(ItemTransferConfirmed.class);
    }

    @Test
    void reactsToAnInTripPostponeOriginedInitiateIdenticallyToAPlanningMoveOne() {
        seedTargetList();
        ItemTransferInitiated initiated = initiatePostponeOnSource();
        assertThat(initiated.origin()).isEqualTo(TransferOrigin.IN_TRIP_POSTPONE);

        processManager.onItemTransferInitiated(initiated);

        List<DomainEvent> targetEvents = eventStore.readStream(targetStreamId);
        assertThat(targetEvents).hasSize(2); // create + ItemAdded
        assertThat(targetEvents.get(1)).isInstanceOf(ItemAdded.class);

        List<DomainEvent> sourceEvents = eventStore.readStream(sourceStreamId);
        // create + add + startTrip + initiated + confirmed
        assertThat(sourceEvents.get(sourceEvents.size() - 1)).isInstanceOf(ItemTransferConfirmed.class);
    }
}
