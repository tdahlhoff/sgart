package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.CreateShoppingListHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidShoppingListNameException;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
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
 * persistence (CLAUDE.md §6). Proves the create-list command path (AC1): a member's create appends
 * {@code ShoppingListCreated} (named or unnamed) to a brand-new {@code list-{id}} stream, a
 * non-member is rejected, and malformed fields map to their localizable codes.
 */
class CreateShoppingListHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final CreateShoppingListHandler handler =
            new CreateShoppingListHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    @Test
    void creatingANamedListAppendsShoppingListCreatedCarryingTheName() {
        seedMembership();
        ShoppingListId listId = ShoppingListId.generate();

        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), "Wocheneinkauf", UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(StreamId.forList(listId));
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ShoppingListCreated.class);
        ShoppingListCreated created = (ShoppingListCreated) events.get(0);
        assertThat(created.householdId()).isEqualTo(householdId);
        assertThat(created.listId()).isEqualTo(listId);
        assertThat(created.name()).isEqualTo(new ShoppingListName("Wocheneinkauf"));
    }

    @Test
    void creatingAnUnnamedListAppendsAnUnnamedShoppingListCreated() {
        seedMembership();
        ShoppingListId listId = ShoppingListId.generate();

        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), null, UUID.randomUUID().toString());

        ShoppingListCreated created = (ShoppingListCreated) eventStore.readStream(StreamId.forList(listId)).get(0);
        assertThat(created.name()).isNull();
    }

    @Test
    void twoCreatesOnTwoFreshStreamsBothSucceedAndCoexist() {
        seedMembership();
        ShoppingListId firstListId = ShoppingListId.generate();
        ShoppingListId secondListId = ShoppingListId.generate();

        handler.handle(MEMBER_SUB, householdId.toString(), firstListId.toString(), "Getränke", UUID.randomUUID().toString());
        handler.handle(MEMBER_SUB, householdId.toString(), secondListId.toString(), null, UUID.randomUUID().toString());

        assertThat(eventStore.readStream(StreamId.forList(firstListId))).hasSize(1);
        assertThat(eventStore.readStream(StreamId.forList(secondListId))).hasSize(1);
    }

    @Test
    void rejectsACreateFromANonMemberWith403() {
        seedMembership();
        ShoppingListId listId = ShoppingListId.generate();

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), listId.toString(), "Getränke", UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
        assertThat(eventStore.readStream(StreamId.forList(listId))).isEmpty();
    }

    @Test
    void mapsAnOverLongNameToNameTooLong() {
        seedMembership();
        String tooLong = "x".repeat(ShoppingListName.MAX_LENGTH + 1);

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB,
                        householdId.toString(),
                        ShoppingListId.generate().toString(),
                        tooLong,
                        UUID.randomUUID().toString()))
                .isInstanceOf(InvalidShoppingListNameException.class)
                .satisfies(thrown -> assertThat(((InvalidShoppingListNameException) thrown).errorDescriptor().code())
                        .isEqualTo("list.nameTooLong"));
    }

    @Test
    void mapsAMalformedListIdToListIdInvalid() {
        seedMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), "not-a-uuid", "Getränke", UUID.randomUUID().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.listIdInvalid"));
    }
}
