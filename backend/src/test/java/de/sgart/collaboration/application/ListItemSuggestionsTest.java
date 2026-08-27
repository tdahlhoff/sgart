package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.query.ListItemSuggestions;
import de.sgart.collaboration.application.query.ListItemSuggestions.ItemSuggestionSummary;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.readmodel.ItemSuggestionReadModel;
import de.sgart.collaboration.domain.readmodel.ItemSuggestionView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.Quantity;
import de.sgart.shared.Unit;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the suggestion query
 * (AC1/AC7): it returns the household's suggestions mapped correctly, rejects a non-member (403),
 * and maps a malformed householdId to 400.
 */
class ListItemSuggestionsTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final HouseholdId householdId = HouseholdId.generate();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();

    private ListItemSuggestions listItemSuggestionsReading(ItemSuggestionReadModel itemSuggestionReadModel) {
        return new ListItemSuggestions(new ResolveMemberIdentity(mappingRepository), itemSuggestionReadModel);
    }

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    @Test
    void forHousehold_returnsTheHouseholdsSuggestionsMappedCorrectly() {
        seedMembership();
        ListItemSuggestions listItemSuggestions = listItemSuggestionsReading(household -> List.of(
                new ItemSuggestionView(new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.LITRE)),
                new ItemSuggestionView(new ItemName("Brot"), null, Quantity.of(1, Unit.PACK))));

        List<ItemSuggestionSummary> summaries = listItemSuggestions.forHousehold(MEMBER_SUB, householdId.toString());

        assertThat(summaries)
                .containsExactly(
                        new ItemSuggestionSummary("Milch", "Bio", "2", "LITRE"),
                        new ItemSuggestionSummary("Brot", null, "1", "PACK"));
    }

    @Test
    void forHousehold_returnsEmptyWhenTheHouseholdHasNoSuggestions() {
        seedMembership();
        ListItemSuggestions listItemSuggestions = listItemSuggestionsReading(household -> List.of());

        assertThat(listItemSuggestions.forHousehold(MEMBER_SUB, householdId.toString())).isEmpty();
    }

    @Test
    void forHousehold_rejectsANonMemberWith403() {
        ListItemSuggestions listItemSuggestions = listItemSuggestionsReading(household -> List.of());

        assertThatThrownBy(() -> listItemSuggestions.forHousehold("stranger-sub", householdId.toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void forHousehold_mapsAMalformedHouseholdIdToHouseholdIdInvalid() {
        ListItemSuggestions listItemSuggestions = listItemSuggestionsReading(household -> List.of());

        assertThatThrownBy(() -> listItemSuggestions.forHousehold(MEMBER_SUB, "not-a-uuid"))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.householdIdInvalid"));
    }
}
