package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.CompleteTripHandler;
import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.application.exception.TripNotCompletableApplicationException;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.TripCompletedForList;
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
 * persistence (CLAUDE.md §6). Proves the complete-trip command path (Story 3.4, AC4): a member's
 * completion appends {@code TripCompletedForList} (plus zero or more {@code ItemDiscarded}), an
 * OPEN list is 409, an unknown list is 404, and a non-member is 403. Mirrors {@link
 * StartTripHandlerTest}.
 */
class CompleteTripHandlerTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final StartTripHandler startTripHandler = new StartTripHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final CompleteTripHandler handler = new CompleteTripHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final StreamId listStreamId = StreamId.forList(listId);
    private final TripId tripId = TripId.generate();

    private void seedListAndMembership() {
        ShoppingList list = ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        eventStore.append(AggregateVersion.initial(listStreamId), list.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    private void startTrip() {
        startTripHandler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), tripId.toString(), List.of(StoreId.generate().toString()), UUID.randomUUID().toString());
    }

    @Test
    void completingAnInTripListRaisesTripCompletedForList() {
        seedListAndMembership();
        startTrip();

        handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), tripId.toString(), UUID.randomUUID().toString());

        List<DomainEvent> events = eventStore.readStream(listStreamId);
        assertThat(events.stream().filter(e -> e instanceof TripCompletedForList).count()).isEqualTo(1);
        TripCompletedForList completed = (TripCompletedForList) events.stream()
                .filter(e -> e instanceof TripCompletedForList).findFirst().orElseThrow();
        assertThat(completed.listId()).isEqualTo(listId);
        assertThat(completed.tripId()).isEqualTo(tripId);
    }

    @Test
    void completingAnOpenListIsRefusedWith409() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), tripId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(TripNotCompletableApplicationException.class);
    }

    @Test
    void completingAnUnknownListIsNotFound() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), ShoppingListId.generate().toString(), tripId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(ShoppingListNotFoundException.class);
    }

    @Test
    void rejectsACompletionFromANonMemberWith403() {
        seedListAndMembership();
        startTrip();

        assertThatThrownBy(() -> handler.handle("stranger-sub", householdId.toString(), listId.toString(), tripId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void mapsAMalformedCommandIdToInvalidCommandEnvelope() {
        seedListAndMembership();

        assertThatThrownBy(() -> handler.handle(MEMBER_SUB, householdId.toString(), listId.toString(), tripId.toString(), "not-a-uuid"))
                .isInstanceOf(InvalidCommandEnvelopeException.class);
    }
}
