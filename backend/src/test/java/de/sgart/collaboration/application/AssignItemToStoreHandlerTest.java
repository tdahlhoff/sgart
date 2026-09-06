package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.command.AssignItemToStoreHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ItemTransferInProgressApplicationException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.TransferOrigin;
import de.sgart.collaboration.domain.event.ItemAssignedToStore;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
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
import de.sgart.shared.Unit;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the assign-item-to-store command path (AC1, AC5, AC7): a
 * member's assign appends {@code ItemAssignedToStore}, a same-store re-assign appends nothing
 * (convergent no-op), an unknown item is 404, an unknown/cross-household list is 404, and a
 * non-member is rejected (403).
 */
class AssignItemToStoreHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddItemHandler addItemHandler = new AddItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final AssignItemToStoreHandler handler =
            new AssignItemToStoreHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final StreamId streamId = StreamId.forList(listId);

    private void seedListAndMembership() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), list.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    private ItemId seedItem(String name) {
        ItemId itemId = ItemId.generate();
        addItemHandler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), name, null, "1", "PIECE",
                UUID.randomUUID().toString());
        return itemId;
    }

    /** Reserves {@code itemId} by appending {@code ItemTransferInitiated} directly to the stream — the fail-fast lock's precondition (Story 3.6, AC4). */
    private void reserveItemForTransfer(ItemId itemId) {
        List<DomainEvent> history = eventStore.readStream(streamId);
        ItemTransferInitiated initiated = new ItemTransferInitiated(
                EventId.generate(), householdId, listId, itemId, ShoppingListId.generate(), new ItemName("Milch"),
                null, Quantity.of(1, Unit.PIECE), TransferOrigin.PLANNING_MOVE);
        eventStore.append(AggregateVersion.of(streamId, history.size()), List.of(initiated), CommandId.generate());
    }

    @Test
    void assigningAnItemAppendsItemAssignedToStore() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        StoreId storeId = StoreId.generate();

        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), storeId.toString(),
                UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOf(ItemAssignedToStore.class);
        assertThat(((ItemAssignedToStore) events.get(2)).storeId()).isEqualTo(storeId);
    }

    @Test
    void assigningAnItemToItsCurrentStoreAgainAppendsNothing() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        StoreId storeId = StoreId.generate();
        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), storeId.toString(),
                UUID.randomUUID().toString());

        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), storeId.toString(),
                UUID.randomUUID().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(3);
    }

    @Test
    void assigningAnUnknownItemIsNotFound() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), ItemId.generate().toString(),
                        StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotFoundApplicationException.class)
                .satisfies(thrown -> assertThat(((ItemNotFoundApplicationException) thrown).errorDescriptor().code())
                        .isEqualTo("item.notFound"));
    }

    @Test
    void assigningOnAnUnknownListIsNotFound() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), ShoppingListId.generate().toString(),
                        ItemId.generate().toString(), StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void assigningOnAListInAnotherHouseholdIsNotFound() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, otherHouseholdId.toString(), listId.toString(), itemId.toString(),
                        StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void assigningAnItemCurrentlyReservedByATransferIsRejectedWith409() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        reserveItemForTransfer(itemId);

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(),
                        StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemTransferInProgressApplicationException.class);
    }

    @Test
    void rejectsAnAssignFromANonMemberWith403() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), listId.toString(), itemId.toString(),
                        StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void mapsAMalformedStoreIdToStoreIdInvalid() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), "not-a-uuid",
                        UUID.randomUUID().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.storeIdInvalid"));
    }
}
