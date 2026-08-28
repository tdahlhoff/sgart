package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.query.ListOpenLists;
import de.sgart.collaboration.application.query.ListOpenLists.ShoppingListSummary;
import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.ShoppingListId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the list query (AC1,
 * AC2, AC4): it returns exactly the caller's Open lists in creation order (excluding a Done list),
 * rejects a non-member (403), and is side-effect free.
 */
class ListOpenListsTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final HouseholdId householdId = HouseholdId.generate();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();

    private ListOpenLists listOpenListsReading(ShoppingListReadModel shoppingListReadModel) {
        return new ListOpenLists(new ResolveMemberIdentity(mappingRepository), shoppingListReadModel);
    }

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    @Test
    void forHousehold_returnsTheOpenListsInCreationOrder() {
        seedMembership();
        ShoppingListId drinksId = ShoppingListId.generate();
        ShoppingListId unnamedId = ShoppingListId.generate();
        ListOpenLists listOpenLists = listOpenListsReading(id -> List.of(
                new ShoppingListView(drinksId, new ShoppingListName("Getränke"), ListStatus.OPEN, 0),
                new ShoppingListView(unnamedId, null, ListStatus.OPEN, 0)));

        List<ShoppingListSummary> summaries = listOpenLists.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(summaries)
                .containsExactly(
                        new ShoppingListSummary(drinksId.toString(), "Getränke", "OPEN", 0),
                        new ShoppingListSummary(unnamedId.toString(), null, "OPEN", 0));
    }

    @Test
    void forHousehold_includesAnInTripList_withItsStatus() {
        // Story 3.1, AC5: an In-Trip list is part of the "active, not archived" set.
        seedMembership();
        ShoppingListId openId = ShoppingListId.generate();
        ShoppingListId inTripId = ShoppingListId.generate();
        ListOpenLists listOpenLists = listOpenListsReading(id -> List.of(
                new ShoppingListView(openId, new ShoppingListName("Getränke"), ListStatus.OPEN, 0),
                new ShoppingListView(inTripId, new ShoppingListName("Wocheneinkauf"), ListStatus.IN_TRIP, 3)));

        List<ShoppingListSummary> summaries = listOpenLists.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(summaries)
                .containsExactly(
                        new ShoppingListSummary(openId.toString(), "Getränke", "OPEN", 0),
                        new ShoppingListSummary(inTripId.toString(), "Wocheneinkauf", "IN_TRIP", 3));
    }

    @Test
    void forHousehold_theOrdinalPositionCountsAnInTripListAmongOpenLists() {
        // Story 3.1, Cl. 3: the "Liste N" ordinal is derived client-side from array position, so the
        // server-returned order across [Open, In-Trip, Open] must include all three in creation order.
        seedMembership();
        ShoppingListId firstId = ShoppingListId.generate();
        ShoppingListId secondId = ShoppingListId.generate();
        ShoppingListId thirdId = ShoppingListId.generate();
        ListOpenLists listOpenLists = listOpenListsReading(id -> List.of(
                new ShoppingListView(firstId, null, ListStatus.OPEN, 0),
                new ShoppingListView(secondId, null, ListStatus.IN_TRIP, 0),
                new ShoppingListView(thirdId, null, ListStatus.OPEN, 0)));

        List<ShoppingListSummary> summaries = listOpenLists.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(summaries).extracting(ShoppingListSummary::listId)
                .containsExactly(firstId.toString(), secondId.toString(), thirdId.toString());
    }

    @Test
    void forHousehold_excludesADoneList() {
        seedMembership();
        ShoppingListId openId = ShoppingListId.generate();
        ShoppingListId doneId = ShoppingListId.generate();
        ListOpenLists listOpenLists = listOpenListsReading(id -> List.of(
                new ShoppingListView(openId, new ShoppingListName("Getränke"), ListStatus.OPEN, 0),
                new ShoppingListView(doneId, new ShoppingListName("Erledigt"), ListStatus.DONE, 0)));

        List<ShoppingListSummary> summaries = listOpenLists.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(summaries).containsExactly(new ShoppingListSummary(openId.toString(), "Getränke", "OPEN", 0));
    }

    @Test
    void forHousehold_returnsEmptyWhenTheHouseholdHasNoLists() {
        seedMembership();
        ListOpenLists listOpenLists = listOpenListsReading(id -> List.of());

        assertThat(listOpenLists.forHousehold(MEMBER_SUB, householdId.toString())).isEmpty();
    }

    @Test
    void forHousehold_rejectsANonMemberWith403() {
        // No membership seeded for this caller.
        ListOpenLists listOpenLists = listOpenListsReading(id -> List.of());

        assertThatThrownBy(() -> listOpenLists.forHousehold("stranger-sub", householdId.toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void forHousehold_mapsAMalformedHouseholdIdToHouseholdIdInvalid() {
        ListOpenLists listOpenLists = listOpenListsReading(id -> List.of());

        assertThatThrownBy(() -> listOpenLists.forHousehold(MEMBER_SUB, "not-a-uuid"))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.householdIdInvalid"));
    }

    @Test
    void forHousehold_isSideEffectFreeSoASecondCallReturnsTheSameRows() {
        seedMembership();
        ShoppingListId listId = ShoppingListId.generate();
        ListOpenLists listOpenLists = listOpenListsReading(
                id -> List.of(new ShoppingListView(listId, new ShoppingListName("Getränke"), ListStatus.OPEN, 0)));

        List<ShoppingListSummary> first = listOpenLists.forHousehold(MEMBER_SUB, householdId.toString());
        List<ShoppingListSummary> second = listOpenLists.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(first).isEqualTo(second);
    }
}
