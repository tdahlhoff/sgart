package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddStoreToTripHandler;
import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.TripNotFoundException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.StoreAddedToTrip;
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
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the add-store-to-trip command path (Story 3.2, AC3, AC5): a
 * member's add appends {@code StoreAddedToTrip} to the trip stream, an already-in-trip store
 * appends nothing (convergent no-op), a non-member is rejected (403), a malformed id is 400, and
 * an unknown/cross-household trip is 404.
 */
class AddStoreToTripHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final StartTripHandler startTripHandler =
            new StartTripHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final AddStoreToTripHandler handler =
            new AddStoreToTripHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final StoreId initialStoreId = StoreId.generate();

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    private TripId seedActiveTrip() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forList(listId)), list.uncommittedEvents(), CommandId.generate());
        seedMembership();
        TripId tripId = TripId.generate();
        startTripHandler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), tripId.toString(),
                List.of(initialStoreId.toString()), UUID.randomUUID().toString());

        // 3.1's process manager isn't wired in this unit test (no EventStore listener); seed the
        // ShoppingTrip stream directly the way the process manager would, mirroring
        // TripStartProcessManagerTest's fixture style.
        de.sgart.collaboration.domain.ShoppingTrip trip = de.sgart.collaboration.domain.ShoppingTrip.start(
                tripId, householdId, listId, List.of(initialStoreId), CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forTrip(tripId)), trip.uncommittedEvents(), CommandId.generate());
        return tripId;
    }

    @Test
    void addingAStoreAppendsStoreAddedToTrip() {
        TripId tripId = seedActiveTrip();
        StoreId newStore = StoreId.generate();

        handler.handle(
                MEMBER_SUB, householdId.toString(), tripId.toString(), newStore.toString(),
                UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(StreamId.forTrip(tripId));
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(StoreAddedToTrip.class);
        assertThat(((StoreAddedToTrip) events.get(1)).storeId()).isEqualTo(newStore);
    }

    @Test
    void addingAStoreAlreadyInTheTripAppendsNothing() {
        TripId tripId = seedActiveTrip();

        handler.handle(
                MEMBER_SUB, householdId.toString(), tripId.toString(), initialStoreId.toString(),
                UUID.randomUUID().toString());

        assertThat(eventStore.readStream(StreamId.forTrip(tripId))).hasSize(1);
    }

    @Test
    void rejectsAnAddFromANonMemberWith403() {
        TripId tripId = seedActiveTrip();

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), tripId.toString(), StoreId.generate().toString(),
                        UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void mapsAMalformedTripIdToTripIdInvalid() {
        seedMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), "not-a-uuid", StoreId.generate().toString(),
                        UUID.randomUUID().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class);
    }

    @Test
    void addingToAnUnknownTripIsNotFound() {
        seedMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), TripId.generate().toString(),
                        StoreId.generate().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void addingToATripInAnotherHouseholdIsNotFound() {
        TripId tripId = seedActiveTrip();
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, otherHouseholdId.toString(), tripId.toString(), StoreId.generate().toString(),
                        UUID.randomUUID().toString()))
                .isInstanceOf(TripNotFoundException.class);
    }
}
