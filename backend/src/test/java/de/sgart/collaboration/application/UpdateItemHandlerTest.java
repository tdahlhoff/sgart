package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.command.UpdateItemHandler;
import de.sgart.collaboration.application.exception.DuplicateItemApplicationException;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ItemUpdated;
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
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the update-item command path (AC3, AC5, AC8): a member's
 * update appends {@code ItemUpdated}, an unchanged update appends nothing (convergent no-op), a
 * collision with a different item is 409, an unknown item is 404, an unknown/cross-household list
 * is 404, and a non-member is rejected (403).
 */
class UpdateItemHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddItemHandler addItemHandler = new AddItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final UpdateItemHandler handler =
            new UpdateItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final StreamId streamId = StreamId.forList(listId);

    private void seedListAndMembership() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), list.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    private ItemId seedItem(String name, String note) {
        ItemId itemId = ItemId.generate();
        addItemHandler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), name, note, "1", "PIECE",
                UUID.randomUUID().toString());
        return itemId;
    }

    @Test
    void updatingAnItemAppendsItemUpdated() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch", "Bio");

        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), "Milch", "Bio 1,5%", "2",
                "PIECE", UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOf(ItemUpdated.class);
        assertThat(((ItemUpdated) events.get(2)).note().value()).isEqualTo("Bio 1,5%");
    }

    @Test
    void updatingAnItemToItsCurrentValuesAppendsNothing() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch", "Bio");

        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), "Milch", "Bio", "1", "PIECE",
                UUID.randomUUID().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void updatingAMissingItemIsNotFound() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), ItemId.generate().toString(), "Milch",
                        null, "1", "PIECE", UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotFoundApplicationException.class)
                .satisfies(thrown -> assertThat(((ItemNotFoundApplicationException) thrown).errorDescriptor().code())
                        .isEqualTo("item.notFound"));
    }

    @Test
    void updatingAnItemToCollideWithADifferentItemIsAConflict() {
        seedListAndMembership();
        seedItem("Milch", null);
        ItemId brotId = seedItem("Brot", null);

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), brotId.toString(), "Milch", null, "1",
                        "PIECE", UUID.randomUUID().toString()))
                .isInstanceOf(DuplicateItemApplicationException.class);
    }

    @Test
    void updatingOnAnUnknownListIsNotFound() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), ShoppingListId.generate().toString(),
                        ItemId.generate().toString(), "Milch", null, "1", "PIECE", UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void updatingOnAListInAnotherHouseholdIsNotFound() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch", null);
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, otherHouseholdId.toString(), listId.toString(), itemId.toString(), "Milch2", null,
                        "1", "PIECE", UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void rejectsAnUpdateFromANonMemberWith403() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch", null);

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), listId.toString(), itemId.toString(), "Milch2", null,
                        "1", "PIECE", UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void mapsAMalformedListIdToListIdInvalid() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), "not-a-uuid", ItemId.generate().toString(), "Milch", null,
                        "1", "PIECE", UUID.randomUUID().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.listIdInvalid"));
    }
}
