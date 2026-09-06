package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.command.MoveItemHandler;
import de.sgart.collaboration.application.exception.InvalidMoveTargetException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ItemTransferInProgressApplicationException;
import de.sgart.collaboration.application.exception.MoveTargetNotOpenException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.TransferOrigin;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
import de.sgart.collaboration.domain.exception.DuplicateItemException;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.MemberId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import de.sgart.shared.Unit;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the move-item command path (AC1, AC5, AC8; reshaped Story 3.6,
 * AC1/AC4): a member's move appends {@code ItemTransferInitiated} to the <em>source</em> stream only
 * (the target add is the process manager's job, proven separately in {@code
 * ItemTransferProcessManagerTest}), reserving the item rather than removing it; a non-member is
 * rejected, an unknown/cross-household source or target is 404, a non-Open target is 409, a
 * same-list move is 400, an unknown source item is 404, a concurrent source write is 409, a move on
 * an item already reserved to a different target is 409 {@code item.transferInProgress}, and a
 * lost-response retry naming the same target is a convergent no-op (no re-reservation, no 404).
 */
class MoveItemHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddItemHandler addItemHandler = new AddItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final MoveItemHandler handler = new MoveItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId sourceListId = ShoppingListId.generate();
    private final ShoppingListId targetListId = ShoppingListId.generate();
    private final StreamId sourceStreamId = StreamId.forList(sourceListId);
    private final StreamId targetStreamId = StreamId.forList(targetListId);

    private void seedListAndMembership(ShoppingListId listId) {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        eventStore.append(AggregateVersion.initial(StreamId.forList(listId)), list.uncommittedEvents(), CommandId.generate());
    }

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    private ItemId seedItemOn(ShoppingListId listId) {
        ItemId itemId = ItemId.generate();
        addItemHandler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), "Milch", null, "1", "PIECE",
                UUID.randomUUID().toString());
        return itemId;
    }

    private void startTrip(ShoppingListId listId) {
        StreamId streamId = StreamId.forList(listId);
        ShoppingList list = ShoppingList.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = list.version();
        list.startTrip(de.sgart.shared.TripId.generate(), List.of(de.sgart.shared.StoreId.generate()), CommandId.generate());
        eventStore.append(loadedVersion, list.uncommittedEvents(), CommandId.generate());
    }

    @Test
    void movingAnItemAppendsItemTransferInitiatedToTheSourceStreamOnlyAndReservesTheItem() {
        seedListAndMembership(sourceListId);
        seedListAndMembership(targetListId);
        seedMembership();
        ItemId itemId = seedItemOn(sourceListId);

        handler.handle(
                MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(),
                targetListId.toString(), UUID.randomUUID().toString());

        List<DomainEvent> sourceEvents = eventStore.readStream(sourceStreamId);
        assertThat(sourceEvents).hasSize(3);
        assertThat(sourceEvents.get(2)).isInstanceOf(ItemTransferInitiated.class);
        ItemTransferInitiated initiated = (ItemTransferInitiated) sourceEvents.get(2);
        assertThat(initiated.itemId()).isEqualTo(itemId);
        assertThat(initiated.sourceListId()).isEqualTo(sourceListId);
        assertThat(initiated.targetListId()).isEqualTo(targetListId);
        assertThat(initiated.origin()).isEqualTo(TransferOrigin.PLANNING_MOVE);
        // The handler never appends to the target — that is the process manager's job.
        assertThat(eventStore.readStream(targetStreamId)).hasSize(1);
        // The item stays reserved on the source's folded state — its (name, note) key is still
        // taken, so re-adding the same key is rejected (mirrors ShoppingListItemsTest's convention;
        // the pre-3.6 move removed the item, so this would have succeeded before).
        ShoppingList rehydratedSource = ShoppingList.rehydrate(sourceStreamId, sourceEvents);
        assertThatThrownBy(() -> rehydratedSource.addItem(
                        ItemId.generate(), new de.sgart.collaboration.domain.ItemName("Milch"), null,
                        Quantity.of(1, Unit.PIECE), CommandId.generate()))
                .isInstanceOf(DuplicateItemException.class);
    }

    @Test
    void movingToATargetThatIsNotOpenIsRejectedWith409() {
        // The synchronous target-OPEN pre-check (Task 9) — fast feedback for the common case,
        // checked before the source is mutated so a stale client can never strand an item.
        seedListAndMembership(sourceListId);
        seedListAndMembership(targetListId);
        seedMembership();
        ItemId itemId = seedItemOn(sourceListId);
        startTrip(targetListId); // target is now IN_TRIP, not OPEN

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(),
                        targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(MoveTargetNotOpenException.class);
        // The source is never mutated — the pre-check runs before any append (only the initial
        // ShoppingListCreated + ItemAdded are on the source stream).
        assertThat(eventStore.readStream(sourceStreamId)).hasSize(2);
    }

    @Test
    void movingAnItemAlreadyReservedToADifferentTargetIsRejectedWith409() {
        seedListAndMembership(sourceListId);
        seedListAndMembership(targetListId);
        ShoppingListId otherTargetListId = ShoppingListId.generate();
        seedListAndMembership(otherTargetListId);
        seedMembership();
        ItemId itemId = seedItemOn(sourceListId);
        handler.handle(
                MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(),
                targetListId.toString(), UUID.randomUUID().toString());

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(),
                        otherTargetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemTransferInProgressApplicationException.class);
    }

    @Test
    void retryingTheSameMoveWithTheSameCommandIdAndTargetIsAConvergentNoOp() {
        // Lost-response idempotency (Story 3.6, AC4): a client retry after a lost 200 must succeed
        // again, not 404 or double-reserve — closing the old lost-response idempotency defect.
        seedListAndMembership(sourceListId);
        seedListAndMembership(targetListId);
        seedMembership();
        ItemId itemId = seedItemOn(sourceListId);
        String commandId = UUID.randomUUID().toString();
        handler.handle(
                MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(),
                targetListId.toString(), commandId);
        int eventCountAfterFirstCall = eventStore.readStream(sourceStreamId).size();

        assertThatCode(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(),
                        targetListId.toString(), commandId))
                .doesNotThrowAnyException();

        assertThat(eventStore.readStream(sourceStreamId)).hasSize(eventCountAfterFirstCall);
    }

    @Test
    void rejectsAMoveFromANonMemberWith403() {
        seedListAndMembership(sourceListId);
        seedListAndMembership(targetListId);
        seedMembership();
        ItemId itemId = seedItemOn(sourceListId);

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), sourceListId.toString(), itemId.toString(),
                        targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
        assertThat(eventStore.readStream(sourceStreamId)).hasSize(2);
    }

    @Test
    void movingFromAnUnknownSourceIsNotFound() {
        seedListAndMembership(targetListId);
        seedMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), ShoppingListId.generate().toString(),
                        ItemId.generate().toString(), targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void movingToAnUnknownTargetIsNotFound() {
        seedListAndMembership(sourceListId);
        seedMembership();
        ItemId itemId = seedItemOn(sourceListId);

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(),
                        ShoppingListId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void movingFromASourceInAnotherHouseholdIsNotFound() {
        seedListAndMembership(sourceListId);
        seedListAndMembership(targetListId);
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, otherHouseholdId.toString(), sourceListId.toString(),
                        ItemId.generate().toString(), targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void movingToATargetInAnotherHouseholdIsNotFound() {
        seedListAndMembership(sourceListId);
        seedMembership();
        ItemId itemId = seedItemOn(sourceListId);
        ShoppingListId foreignTargetId = ShoppingListId.generate();
        ShoppingList foreignTarget = ShoppingList.create(
                foreignTargetId, HouseholdId.generate(), new ShoppingListName("Fremd"), CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forList(foreignTargetId)), foreignTarget.uncommittedEvents(),
                CommandId.generate());

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(),
                        foreignTargetId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void movingToTheSameListAsTheSourceIsRejectedWith400() {
        seedListAndMembership(sourceListId);
        seedMembership();
        ItemId itemId = seedItemOn(sourceListId);

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(),
                        sourceListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(InvalidMoveTargetException.class)
                .satisfies(thrown -> assertThat(((InvalidMoveTargetException) thrown).errorDescriptor().code())
                        .isEqualTo("list.moveTargetSameAsSource"));
    }

    @Test
    void movingAnUnknownSourceItemIsNotFound() {
        seedListAndMembership(sourceListId);
        seedListAndMembership(targetListId);
        seedMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), sourceListId.toString(),
                        ItemId.generate().toString(), targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotFoundApplicationException.class);
    }

    @Test
    void propagatesAConcurrencyConflictWhenTheSourceStreamAdvancesUnderTheMove() {
        seedListAndMembership(sourceListId);
        seedListAndMembership(targetListId);
        seedMembership();
        ItemId itemId = seedItemOn(sourceListId);
        // A competing writer lands an event on the source stream between this handler's load and its
        // append, so the loaded expected version is stale — the append must fail with a
        // ConcurrencyConflictException (AD-8), never overwrite.
        MoveItemHandler racingHandler =
                new MoveItemHandler(racingStoreThatAdvancesSourceOnFirstRead(), new ResolveMemberIdentity(mappingRepository));

        assertThatThrownBy(() -> racingHandler.handle(
                        MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(),
                        targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    /**
     * An {@link EventStore} decorator that, the first time the <em>source</em> stream is read,
     * appends a competing {@link ItemAdded} to advance its version — simulating a concurrent writer
     * between the handler's load and its append, so the handler's expected version is stale (AD-8).
     * Mirrors {@code AddStoreHandlerTest.racingStoreThatAdvancesOnFirstRead}.
     */
    private EventStore racingStoreThatAdvancesSourceOnFirstRead() {
        return new EventStore() {
            private boolean raced = false;

            @Override
            public List<DomainEvent> readStream(StreamId id) {
                List<DomainEvent> events = eventStore.readStream(id);
                if (!raced && id.equals(sourceStreamId)) {
                    raced = true;
                    eventStore.append(
                            AggregateVersion.of(id, events.size()),
                            List.of(new ItemAdded(
                                    EventId.generate(),
                                    householdId,
                                    sourceListId,
                                    ItemId.generate(),
                                    new ItemName("Konkurrent"),
                                    null,
                                    Quantity.of(1, Unit.PIECE))),
                            CommandId.generate());
                }
                return events;
            }

            @Override
            public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
                eventStore.append(expectedVersion, events, commandId);
            }
        };
    }
}
