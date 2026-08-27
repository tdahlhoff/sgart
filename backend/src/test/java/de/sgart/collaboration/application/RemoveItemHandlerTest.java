package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.command.RemoveItemHandler;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ItemRemoved;
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
 * persistence (CLAUDE.md §6). Proves the remove-item command path (AC4, AC5, AC8): removing an
 * existing item appends {@code ItemRemoved}, removing an unknown item skips the append (convergent
 * no-op, AD-8), an unknown/cross-household list is 404, and a non-member is rejected (403).
 */
class RemoveItemHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddItemHandler addItemHandler = new AddItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final RemoveItemHandler handler =
            new RemoveItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final StreamId streamId = StreamId.forList(listId);

    private void seedListAndMembership() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), list.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    private ItemId seedItem() {
        ItemId itemId = ItemId.generate();
        addItemHandler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), "Milch", null, "1", "PIECE",
                UUID.randomUUID().toString());
        return itemId;
    }

    @Test
    void removingAnExistingItemAppendsItemRemoved() {
        seedListAndMembership();
        ItemId itemId = seedItem();

        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOf(ItemRemoved.class);
        assertThat(((ItemRemoved) events.get(2)).itemId()).isEqualTo(itemId);
    }

    @Test
    void removingAnUnknownItemSkipsTheAppend() {
        seedListAndMembership();

        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), ItemId.generate().toString(),
                UUID.randomUUID().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(1);
    }

    @Test
    void removingAnAlreadyRemovedItemSkipsTheAppend() {
        seedListAndMembership();
        ItemId itemId = seedItem();
        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString());

        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(3);
    }

    @Test
    void removingFromAnUnknownListIsNotFound() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), ShoppingListId.generate().toString(),
                        ItemId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void removingFromAListInAnotherHouseholdIsNotFound() {
        seedListAndMembership();
        ItemId itemId = seedItem();
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, otherHouseholdId.toString(), listId.toString(), itemId.toString(),
                        UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void rejectsARemoveFromANonMemberWith403() {
        seedListAndMembership();
        ItemId itemId = seedItem();

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), listId.toString(), itemId.toString(),
                        UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
    }
}
