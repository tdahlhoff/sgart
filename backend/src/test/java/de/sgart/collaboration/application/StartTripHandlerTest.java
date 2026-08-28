package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidTripStoreSelectionException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.application.exception.TripNotStartableApplicationException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.TripStartedForList;
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
 * persistence (CLAUDE.md §6). Proves the start-trip command path (AC1, AC2, AC3, AC7): a member's
 * start appends {@code TripStartedForList} to the list stream, a non-member is rejected, a
 * malformed id is 400, empty stores is 400, an unknown/cross-household list is 404, and an
 * already In-Trip (or Done) list is 409 (AC2, at-most-one).
 */
class StartTripHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final StartTripHandler handler = new StartTripHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final StreamId listStreamId = StreamId.forList(listId);
    private final StoreId storeId = StoreId.generate();

    private void seedList() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        eventStore.append(AggregateVersion.initial(listStreamId), list.uncommittedEvents(), CommandId.generate());
    }

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    @Test
    void startingATripAppendsTripStartedForListToTheListStream() {
        seedList();
        seedMembership();
        TripId tripId = TripId.generate();

        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), tripId.toString(),
                List.of(storeId.toString()), UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(listStreamId);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(TripStartedForList.class);
        TripStartedForList started = (TripStartedForList) events.get(1);
        assertThat(started.tripId()).isEqualTo(tripId);
        assertThat(started.listId()).isEqualTo(listId);
        assertThat(started.householdId()).isEqualTo(householdId);
        assertThat(started.storeIds()).containsExactly(storeId);
    }

    @Test
    void rejectsAStartFromANonMemberWith403() {
        seedList();
        seedMembership();

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), listId.toString(), TripId.generate().toString(),
                        List.of(storeId.toString()), UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
        assertThat(eventStore.readStream(listStreamId)).hasSize(1);
    }

    @Test
    void rejectsAMalformedListIdWith400() {
        seedMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), "not-a-uuid", TripId.generate().toString(),
                        List.of(storeId.toString()), UUID.randomUUID().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class);
    }

    @Test
    void rejectsAnEmptyStoreSelectionWith400() {
        seedList();
        seedMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), TripId.generate().toString(),
                        List.of(), UUID.randomUUID().toString()))
                .isInstanceOf(InvalidTripStoreSelectionException.class);
        assertThat(eventStore.readStream(listStreamId)).hasSize(1);
    }

    @Test
    void startingFromAnUnknownListIsNotFound() {
        seedMembership();

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), ShoppingListId.generate().toString(),
                        TripId.generate().toString(), List.of(storeId.toString()), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void startingFromAListInAnotherHouseholdIsNotFound() {
        seedList();
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, otherHouseholdId.toString(), listId.toString(), TripId.generate().toString(),
                        List.of(storeId.toString()), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void startingASecondTripOnAnAlreadyInTripListIsRefusedWith409() {
        seedList();
        seedMembership();
        handler.handle(
                MEMBER_SUB, householdId.toString(), listId.toString(), TripId.generate().toString(),
                List.of(storeId.toString()), UUID.randomUUID().toString());

        assertThatThrownBy(() -> handler.handle(
                        MEMBER_SUB, householdId.toString(), listId.toString(), TripId.generate().toString(),
                        List.of(storeId.toString()), UUID.randomUUID().toString()))
                .isInstanceOf(TripNotStartableApplicationException.class);
    }
}
