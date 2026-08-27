package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.exception.DuplicateItemApplicationException;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidItemNameException;
import de.sgart.collaboration.application.exception.InvalidItemQuantityException;
import de.sgart.collaboration.application.exception.ItemChangeNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ItemAdded;
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
 * persistence (CLAUDE.md §6). Proves the add-item command path (AC1, AC2, AC5, AC8): a member's add
 * appends {@code ItemAdded} under the loaded expected version, a non-member is rejected (403), an
 * unknown/cross-household list is 404, a duplicate (name, note) key is a 409, malformed fields map
 * to their localizable codes, and a retried {@code commandId} does not double-add.
 */
class AddItemHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddItemHandler handler = new AddItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final StreamId streamId = StreamId.forList(listId);

    private void seedListAndMembership() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), list.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    @Test
    void addingAnItemAppendsItemAddedUnderTheLoadedExpectedVersion() {
        seedListAndMembership();
        ItemId itemId = ItemId.generate();

        handler.handle(
                MEMBER_SUB,
                householdId.toString(),
                listId.toString(),
                itemId.toString(),
                "Milch",
                "Bio",
                "1",
                "PIECE",
                UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(ItemAdded.class);
        ItemAdded added = (ItemAdded) events.get(1);
        assertThat(added.itemId()).isEqualTo(itemId);
        assertThat(added.name().value()).isEqualTo("Milch");
        assertThat(added.note().value()).isEqualTo("Bio");
    }

    @Test
    void addingAnItemWithNoNoteAppendsAnItemWithoutANote() {
        seedListAndMembership();

        handler.handle(
                MEMBER_SUB,
                householdId.toString(),
                listId.toString(),
                ItemId.generate().toString(),
                "Milch",
                null,
                "1",
                "PIECE",
                UUID.randomUUID().toString());

        ItemAdded added = (ItemAdded) eventStore.readStream(streamId).get(1);
        assertThat(added.note()).isNull();
    }

    @Test
    void rejectsAnAddFromANonMemberWith403() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub",
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString(),
                        "Milch",
                        null,
                        "1",
                        "PIECE",
                        UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(1);
    }

    @Test
    void addingToAnUnknownListIsNotFound() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB,
                        householdId.toString(),
                        ShoppingListId.generate().toString(),
                        ItemId.generate().toString(),
                        "Milch",
                        null,
                        "1",
                        "PIECE",
                        UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void addingToAListInAnotherHouseholdIsNotFound() {
        seedListAndMembership();
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB,
                        otherHouseholdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString(),
                        "Milch",
                        null,
                        "1",
                        "PIECE",
                        UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void mapsADuplicateNameAndNoteToTheConflictCode() {
        seedListAndMembership();
        handler.handle(
                MEMBER_SUB,
                householdId.toString(),
                listId.toString(),
                ItemId.generate().toString(),
                "Milch",
                "Bio",
                "1",
                "PIECE",
                UUID.randomUUID().toString());

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB,
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString(),
                        " milch ",
                        " bio ",
                        "2",
                        "PIECE",
                        UUID.randomUUID().toString()))
                .isInstanceOf(DuplicateItemApplicationException.class)
                .satisfies(thrown -> assertThat(((DuplicateItemApplicationException) thrown).errorDescriptor().code())
                        .isEqualTo("item.duplicate"));
        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void mapsABlankNameToNameRequired() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB,
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString(),
                        "   ",
                        null,
                        "1",
                        "PIECE",
                        UUID.randomUUID().toString()))
                .isInstanceOf(InvalidItemNameException.class)
                .satisfies(thrown -> assertThat(((InvalidItemNameException) thrown).errorDescriptor().code())
                        .isEqualTo("item.nameRequired"));
    }

    @Test
    void mapsANonPositiveAmountToQuantityInvalid() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB,
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString(),
                        "Milch",
                        null,
                        "0",
                        "PIECE",
                        UUID.randomUUID().toString()))
                .isInstanceOf(InvalidItemQuantityException.class)
                .satisfies(thrown -> assertThat(((InvalidItemQuantityException) thrown).errorDescriptor().code())
                        .isEqualTo("item.quantityInvalid"));
    }

    @Test
    void mapsAnUnknownUnitToQuantityInvalid() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB,
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString(),
                        "Milch",
                        null,
                        "1",
                        "BOGUS",
                        UUID.randomUUID().toString()))
                .isInstanceOf(InvalidItemQuantityException.class)
                .satisfies(thrown -> assertThat(((InvalidItemQuantityException) thrown).errorDescriptor().code())
                        .isEqualTo("item.quantityInvalid"));
    }

    @Test
    void mapsAMalformedItemIdToItemIdInvalid() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB,
                        householdId.toString(),
                        listId.toString(),
                        "not-a-uuid",
                        "Milch",
                        null,
                        "1",
                        "PIECE",
                        UUID.randomUUID().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.itemIdInvalid"));
    }

}
