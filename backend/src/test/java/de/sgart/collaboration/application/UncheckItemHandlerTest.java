package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.command.CheckOffItemHandler;
import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.collaboration.application.command.UncheckItemHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.ItemNotDuringTripApplicationException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ItemTransferInProgressApplicationException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.TransferOrigin;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
import de.sgart.collaboration.domain.event.ItemUnchecked;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.MemberId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import de.sgart.shared.Unit;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the uncheck-item command path (Story 3.3, AC2): unchecking a
 * DONE item appends {@code ItemUnchecked}, unchecking an already-OPEN item is a convergent no-op, an
 * unknown item is 404, an unknown list is 404, a non-member is rejected (403), a malformed id is
 * 400, and a not-IN_TRIP list is 409.
 */
class UncheckItemHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddItemHandler addItemHandler = new AddItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final StartTripHandler startTripHandler = new StartTripHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final CheckOffItemHandler checkOffItemHandler = new CheckOffItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final UncheckItemHandler handler = new UncheckItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final StreamId streamId = StreamId.forList(listId);
    private final StoreId tripStoreId = StoreId.generate();

    private void seedListAndMembership() {
        ShoppingList list = ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), list.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    private ItemId seedItem(String name) {
        ItemId itemId = ItemId.generate();
        addItemHandler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), name, null, "1", "PIECE", UUID.randomUUID().toString());
        return itemId;
    }

    private void startTrip() {
        startTripHandler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), TripId.generate().toString(), List.of(tripStoreId.toString()), UUID.randomUUID().toString());
    }

    private void checkOff(ItemId itemId) {
        checkOffItemHandler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString());
    }

    /** Reserves {@code itemId} by appending {@code ItemTransferInitiated} directly to the stream — the fail-fast lock's precondition (Story 3.6, AC4). */
    private void reserveItemForTransfer(ItemId itemId) {
        List<DomainEvent> history = eventStore.readStream(streamId);
        ItemTransferInitiated initiated = new ItemTransferInitiated(
                EventId.generate(), householdId, listId, itemId, ShoppingListId.generate(), new ItemName("Milch"),
                null, Quantity.of(1, Unit.PIECE), TransferOrigin.IN_TRIP_POSTPONE);
        eventStore.append(AggregateVersion.of(streamId, history.size()), List.of(initiated), CommandId.generate());
    }

    @Test
    void uncheckingADoneItemAppendsItemUnchecked() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();
        checkOff(itemId);
        int sizeAfterCheckOff = eventStore.readStream(streamId).size();

        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(sizeAfterCheckOff + 1);
        assertThat(events.get(events.size() - 1)).isInstanceOf(ItemUnchecked.class);
        assertThat(((ItemUnchecked) events.get(events.size() - 1)).itemId()).isEqualTo(itemId);
    }

    @Test
    void uncheckingAnAlreadyOpenItemIsAConvergentNoOp() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();
        int sizeBefore = eventStore.readStream(streamId).size();

        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(sizeBefore);
    }

    @Test
    void uncheckingAnUnknownItemIsNotFound() {
        seedListAndMembership();
        startTrip();

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), ItemId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotFoundApplicationException.class);
    }

    @Test
    void uncheckingOnAnUnknownListIsNotFound() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), ShoppingListId.generate().toString(), ItemId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void rejectsAnUncheckFromANonMemberWith403() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();

        assertThatThrownBy(() -> handler.handle("stranger-sub", householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void mapsAMalformedItemIdToItemIdInvalid() {
        seedListAndMembership();
        startTrip();

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), "not-a-uuid", UUID.randomUUID().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class);
    }

    @Test
    void uncheckingAnItemCurrentlyReservedByATransferIsRejectedWith409() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();
        checkOff(itemId);
        reserveItemForTransfer(itemId);

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemTransferInProgressApplicationException.class);
    }

    @Test
    void uncheckingOnAnOpenListIsRefusedWith409() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotDuringTripApplicationException.class);
    }
}
