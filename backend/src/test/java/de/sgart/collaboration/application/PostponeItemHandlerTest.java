package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.command.PostponeItemHandler;
import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.ItemNotDuringTripApplicationException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ItemPostponed;
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
 * persistence (CLAUDE.md §6). Proves the postpone-in-place command path (Story 3.3, AC3): a
 * postpone appends {@code ItemPostponed}, a re-postpone is a convergent no-op, an unknown item is
 * 404, an unknown list is 404, a non-member is rejected (403), a malformed id is 400, and a
 * not-IN_TRIP list is 409.
 */
class PostponeItemHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddItemHandler addItemHandler = new AddItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final StartTripHandler startTripHandler = new StartTripHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final PostponeItemHandler handler = new PostponeItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

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

    @Test
    void postponingAnItemAppendsItemPostponed() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();

        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(4);
        assertThat(events.get(3)).isInstanceOf(ItemPostponed.class);
        assertThat(((ItemPostponed) events.get(3)).itemId()).isEqualTo(itemId);
    }

    @Test
    void postponingAnAlreadyPostponedItemIsAConvergentNoOp() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();
        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString());
        int sizeAfterFirst = eventStore.readStream(streamId).size();

        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(sizeAfterFirst);
    }

    @Test
    void postponingAnUnknownItemIsNotFound() {
        seedListAndMembership();
        startTrip();

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), ItemId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotFoundApplicationException.class);
    }

    @Test
    void postponingOnAnUnknownListIsNotFound() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), ShoppingListId.generate().toString(), ItemId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void rejectsAPostponeFromANonMemberWith403() {
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
    void postponingOnAnOpenListIsRefusedWith409() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotDuringTripApplicationException.class);
    }
}
