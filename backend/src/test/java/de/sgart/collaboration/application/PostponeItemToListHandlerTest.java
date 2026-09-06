package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.command.PostponeItemToListHandler;
import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidMoveTargetException;
import de.sgart.collaboration.application.exception.ItemNotDuringTripApplicationException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ItemTransferInProgressApplicationException;
import de.sgart.collaboration.application.exception.MoveTargetNotOpenException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.TransferOrigin;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.MemberId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the postpone-to-list command path (Story 3.3, AC4; reshaped
 * Story 3.6, AC1/AC4): an IN_TRIP source and an OPEN target append {@code ItemTransferInitiated} on
 * the source, reserving the item rather than removing it; a self-target is 400; a not-OPEN target
 * is 409; a not-IN_TRIP source is 409; an unknown item is 404; an unknown source or target list is
 * 404; a non-member is rejected (403); a malformed id is 400; an item already reserved to a
 * different target is 409 {@code item.transferInProgress}; a lost-response retry naming the same
 * target is a convergent no-op.
 */
class PostponeItemToListHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddItemHandler addItemHandler = new AddItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final StartTripHandler startTripHandler = new StartTripHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final PostponeItemToListHandler handler = new PostponeItemToListHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId sourceListId = ShoppingListId.generate();
    private final ShoppingListId targetListId = ShoppingListId.generate();
    private final StreamId sourceStreamId = StreamId.forList(sourceListId);
    private final StoreId tripStoreId = StoreId.generate();

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    private void seedList(ShoppingListId listId, String name) {
        ShoppingList list = ShoppingList.create(listId, householdId, new ShoppingListName(name), CommandId.generate());
        eventStore.append(AggregateVersion.initial(StreamId.forList(listId)), list.uncommittedEvents(), CommandId.generate());
    }

    private ItemId seedItem(ShoppingListId listId, String name) {
        ItemId itemId = ItemId.generate();
        addItemHandler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), name, null, "1", "PIECE", UUID.randomUUID().toString());
        return itemId;
    }

    private void startTrip(ShoppingListId listId) {
        startTripHandler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), TripId.generate().toString(), List.of(tripStoreId.toString()), UUID.randomUUID().toString());
    }

    /** Source IN_TRIP holding one item; target OPEN. Returns the source item id. */
    private ItemId seedInTripSourceAndOpenTarget() {
        seedMembership();
        seedList(sourceListId, "Wocheneinkauf");
        seedList(targetListId, "Getränke");
        ItemId itemId = seedItem(sourceListId, "Milch");
        startTrip(sourceListId);
        return itemId;
    }

    @Test
    void postponingToAnOpenTargetAppendsItemTransferInitiatedOnTheSourceAndReservesTheItem() {
        ItemId itemId = seedInTripSourceAndOpenTarget();

        handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(), targetListId.toString(), UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(sourceStreamId);
        DomainEvent last = events.get(events.size() - 1);
        assertThat(last).isInstanceOf(ItemTransferInitiated.class);
        ItemTransferInitiated initiated = (ItemTransferInitiated) last;
        assertThat(initiated.itemId()).isEqualTo(itemId);
        assertThat(initiated.targetListId()).isEqualTo(targetListId);
        assertThat(initiated.origin()).isEqualTo(TransferOrigin.IN_TRIP_POSTPONE);
    }

    @Test
    void postponingAnItemAlreadyReservedToADifferentTargetIsRejectedWith409() {
        ItemId itemId = seedInTripSourceAndOpenTarget();
        ShoppingListId otherTargetListId = ShoppingListId.generate();
        seedList(otherTargetListId, "Drogerie");
        handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(), targetListId.toString(), UUID.randomUUID().toString());

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(), otherTargetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemTransferInProgressApplicationException.class);
    }

    @Test
    void retryingTheSamePostponeWithTheSameCommandIdAndTargetIsAConvergentNoOp() {
        // Lost-response idempotency (Story 3.6, AC4): a client retry after a lost 200 must succeed
        // again, not 404 or double-reserve.
        ItemId itemId = seedInTripSourceAndOpenTarget();
        String commandId = UUID.randomUUID().toString();
        handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(), targetListId.toString(), commandId);
        int eventCountAfterFirstCall = eventStore.readStream(sourceStreamId).size();

        assertThatCode(() -> handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(), targetListId.toString(), commandId))
                .doesNotThrowAnyException();

        assertThat(eventStore.readStream(sourceStreamId)).hasSize(eventCountAfterFirstCall);
    }

    @Test
    void rejectsASelfTargetWith400() {
        ItemId itemId = seedInTripSourceAndOpenTarget();

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(), sourceListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(InvalidMoveTargetException.class);
    }

    @Test
    void rejectsANotOpenTargetWith409() {
        seedMembership();
        seedList(sourceListId, "Wocheneinkauf");
        seedList(targetListId, "Getränke");
        ItemId itemId = seedItem(sourceListId, "Milch");
        seedItem(targetListId, "Wasser");
        startTrip(sourceListId);
        startTrip(targetListId); // target is now IN_TRIP, not OPEN

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(), targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(MoveTargetNotOpenException.class);
    }

    @Test
    void refusesWhenTheSourceIsNotInTripWith409() {
        seedMembership();
        seedList(sourceListId, "Wocheneinkauf");
        seedList(targetListId, "Getränke");
        ItemId itemId = seedItem(sourceListId, "Milch"); // source stays OPEN

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(), targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotDuringTripApplicationException.class);
    }

    @Test
    void postponingAnUnknownItemIsNotFound() {
        seedInTripSourceAndOpenTarget();

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), ItemId.generate().toString(), targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotFoundApplicationException.class);
    }

    @Test
    void postponingFromAnUnknownSourceListIsNotFound() {
        seedMembership();
        seedList(targetListId, "Getränke");

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), ItemId.generate().toString(), targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void postponingToAnUnknownTargetListIsNotFound() {
        seedMembership();
        seedList(sourceListId, "Wocheneinkauf");
        ItemId itemId = seedItem(sourceListId, "Milch");
        startTrip(sourceListId);

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), itemId.toString(), targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void rejectsAPostponeFromANonMemberWith403() {
        ItemId itemId = seedInTripSourceAndOpenTarget();

        assertThatThrownBy(() -> handler.handle("stranger-sub", householdId.toString(), sourceListId.toString(), itemId.toString(), targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void mapsAMalformedItemIdToItemIdInvalid() {
        seedInTripSourceAndOpenTarget();

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), sourceListId.toString(), "not-a-uuid", targetListId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class);
    }
}
