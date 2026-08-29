package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.command.RerouteItemHandler;
import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ItemNotDuringTripApplicationException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ItemRerouted;
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
 * persistence (CLAUDE.md §6). Proves the reroute-item-in-trip command path (Story 3.2, AC2, AC5,
 * Cl. 1/8): a member's reroute appends {@code ItemRerouted}, a same-store reroute appends nothing
 * (convergent no-op), an unknown item is 404, an unknown/cross-household list is 404, a non-member
 * is rejected (403), a malformed id is 400, and a not-In-Trip (Open) list is 409.
 */
class RerouteItemHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddItemHandler addItemHandler = new AddItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final StartTripHandler startTripHandler =
            new StartTripHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final RerouteItemHandler handler =
            new RerouteItemHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final StreamId streamId = StreamId.forList(listId);
    private final StoreId tripStoreId = StoreId.generate();

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

    private void startTrip() {
        startTripHandler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), TripId.generate().toString(),
                List.of(tripStoreId.toString()), UUID.randomUUID().toString());
    }

    @Test
    void reroutingAnItemAppendsItemRerouted() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();
        StoreId newStore = StoreId.generate();

        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), newStore.toString(),
                UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(4);
        assertThat(events.get(3)).isInstanceOf(ItemRerouted.class);
        assertThat(((ItemRerouted) events.get(3)).storeId()).isEqualTo(newStore);
    }

    @Test
    void reroutingAnItemToItsCurrentStoreAppendsNothing() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();
        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), tripStoreId.toString(),
                UUID.randomUUID().toString());
        int sizeAfterFirst = eventStore.readStream(streamId).size();

        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), tripStoreId.toString(),
                UUID.randomUUID().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(sizeAfterFirst);
    }

    @Test
    void reroutingAnUnknownItemIsNotFound() {
        seedListAndMembership();
        startTrip();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), ItemId.generate().toString(),
                        StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotFoundApplicationException.class);
    }

    @Test
    void reroutingOnAnUnknownListIsNotFound() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), ShoppingListId.generate().toString(),
                        ItemId.generate().toString(), StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void reroutingOnAListInAnotherHouseholdIsNotFound() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, otherHouseholdId.toString(), listId.toString(), itemId.toString(),
                        StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void rejectsARerouteFromANonMemberWith403() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), listId.toString(), itemId.toString(),
                        StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void mapsAMalformedStoreIdToStoreIdInvalid() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        startTrip();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(), "not-a-uuid",
                        UUID.randomUUID().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class);
    }

    @Test
    void reroutingOnAnOpenListIsRefusedWith409() {
        seedListAndMembership();
        ItemId itemId = seedItem("Milch");
        // No startTrip() — the list is still Open.

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), itemId.toString(),
                        StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ItemNotDuringTripApplicationException.class);
    }
}
