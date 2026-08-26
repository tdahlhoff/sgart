package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.query.ListDoneLists;
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
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the archive query
 * (AC2): it returns exactly the caller's Done lists in creation order (excluding Open lists),
 * rejects a non-member (403), and is side-effect free. Seeds a {@code DONE} read-model row
 * directly — a test-double shape, not a fabricated domain transition (Dev Notes).
 */
class ListDoneListsTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final HouseholdId householdId = HouseholdId.generate();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();

    private ListDoneLists listDoneListsReading(ShoppingListReadModel shoppingListReadModel) {
        return new ListDoneLists(new ResolveMemberIdentity(mappingRepository), shoppingListReadModel);
    }

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    @Test
    void doneListsAreReturnedAsAReadOnlyArchiveInCreationOrder() {
        seedMembership();
        ShoppingListId firstDoneId = ShoppingListId.generate();
        ShoppingListId secondDoneId = ShoppingListId.generate();
        ListDoneLists listDoneLists = listDoneListsReading(id -> List.of(
                new ShoppingListView(firstDoneId, new ShoppingListName("Wocheneinkauf"), ListStatus.DONE),
                new ShoppingListView(secondDoneId, null, ListStatus.DONE)));

        List<ShoppingListSummary> summaries = listDoneLists.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(summaries)
                .containsExactly(
                        new ShoppingListSummary(firstDoneId.toString(), "Wocheneinkauf", "DONE"),
                        new ShoppingListSummary(secondDoneId.toString(), null, "DONE"));
    }

    @Test
    void openListsAreExcludedFromTheArchive() {
        seedMembership();
        ShoppingListId openId = ShoppingListId.generate();
        ShoppingListId doneId = ShoppingListId.generate();
        ListDoneLists listDoneLists = listDoneListsReading(id -> List.of(
                new ShoppingListView(openId, new ShoppingListName("Getränke"), ListStatus.OPEN),
                new ShoppingListView(doneId, new ShoppingListName("Alte Liste"), ListStatus.DONE)));

        List<ShoppingListSummary> summaries = listDoneLists.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(summaries).containsExactly(new ShoppingListSummary(doneId.toString(), "Alte Liste", "DONE"));
    }

    @Test
    void anEmptyHouseholdArchiveReturnsAnEmptyList() {
        seedMembership();
        ListDoneLists listDoneLists = listDoneListsReading(id -> List.of());

        assertThat(listDoneLists.forHousehold(MEMBER_SUB, householdId.toString())).isEmpty();
    }

    @Test
    void aNonMemberCannotReadTheArchive() {
        // No membership seeded for this caller.
        ListDoneLists listDoneLists = listDoneListsReading(id -> List.of());

        assertThatThrownBy(() -> listDoneLists.forHousehold("stranger-sub", householdId.toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void theArchiveQueryIsSideEffectFreeSoASecondCallReturnsTheSameRows() {
        seedMembership();
        ShoppingListId doneId = ShoppingListId.generate();
        ListDoneLists listDoneLists = listDoneListsReading(
                id -> List.of(new ShoppingListView(doneId, new ShoppingListName("Getränke"), ListStatus.DONE)));

        List<ShoppingListSummary> first = listDoneLists.forHousehold(MEMBER_SUB, householdId.toString());
        List<ShoppingListSummary> second = listDoneLists.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(first).isEqualTo(second);
    }
}
