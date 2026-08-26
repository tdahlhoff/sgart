package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.RenameShoppingListHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidShoppingListNameException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the rename-list command path (AC3): a member's rename appends
 * {@code ShoppingListRenamed} under the loaded expected version, a non-member is rejected, an
 * unknown list is `404`, a list under another household is `404` (defense-in-depth), malformed
 * fields map to their localizable codes, and a no-change rename appends nothing (convergent no-op,
 * AD-8).
 */
class RenameShoppingListHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final RenameShoppingListHandler handler =
            new RenameShoppingListHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

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
    void renamingAppendsShoppingListRenamedUnderTheLoadedExpectedVersion() {
        seedListAndMembership();

        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), "Getränke", UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(ShoppingListRenamed.class);
        assertThat(((ShoppingListRenamed) events.get(1)).newName()).isEqualTo(new ShoppingListName("Getränke"));
    }

    @Test
    void rejectsARenameFromANonMemberWith403() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), listId.toString(), "Getränke", UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(1); // no ShoppingListRenamed appended
    }

    @Test
    void renamingAnUnknownListIsNotFound() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
        ShoppingListId unknownListId = ShoppingListId.generate();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB,
                        householdId.toString(),
                        unknownListId.toString(),
                        "Getränke",
                        UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void rejectsAListThatBelongsToAnotherHouseholdAsNotFound() {
        seedListAndMembership();
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB,
                        otherHouseholdId.toString(),
                        listId.toString(),
                        "Getränke",
                        UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void mapsABlankNameToNameRequired() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), "   ", UUID.randomUUID().toString()))
                .isInstanceOf(InvalidShoppingListNameException.class)
                .satisfies(thrown -> assertThat(((InvalidShoppingListNameException) thrown).errorDescriptor().code())
                        .isEqualTo("list.nameRequired"));
    }

    @Test
    void mapsAMalformedListIdToListIdInvalid() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), "not-a-uuid", "Getränke", UUID.randomUUID().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.listIdInvalid"));
    }

    @Test
    void renamingToTheCurrentNameAppendsNothing() {
        seedListAndMembership();

        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), "Wocheneinkauf", UUID.randomUUID().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(1); // still just the one creation event
    }
}
