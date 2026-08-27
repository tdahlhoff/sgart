package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.query.ListItems;
import de.sgart.collaboration.application.query.ListItems.ItemSummary;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.readmodel.ItemReadModel;
import de.sgart.collaboration.domain.readmodel.ItemView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.MemberId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.Unit;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the item query (AC6):
 * it returns exactly the list's items in creation order, rejects a non-member (403), and yields an
 * empty list for a list under another household (no data leak).
 */
class ListItemsTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();

    private ListItems listItemsReading(ItemReadModel itemReadModel) {
        return new ListItems(new ResolveMemberIdentity(mappingRepository), itemReadModel);
    }

    /**
     * An item read model answering {@code itemsOf} from the supplied function. {@code householdIdOf}
     * is the projector's lookup (Story 2.5, Cl. 5), never a query's, so this fake answers it empty.
     */
    private record FakeItemReadModel(BiFunction<HouseholdId, ShoppingListId, List<ItemView>> items)
            implements ItemReadModel {

        @Override
        public List<ItemView> itemsOf(HouseholdId householdId, ShoppingListId listId) {
            return items.apply(householdId, listId);
        }

        @Override
        public Optional<HouseholdId> householdIdOf(ItemId itemId) {
            return Optional.empty();
        }
    }

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    @Test
    void forList_returnsTheItemsInCreationOrder() {
        seedMembership();
        ItemId milchId = ItemId.generate();
        ItemId brotId = ItemId.generate();
        ListItems listItems = listItemsReading(new FakeItemReadModel((household, list) -> List.of(
                new ItemView(milchId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE)),
                new ItemView(brotId, new ItemName("Brot"), null, Quantity.of(2, Unit.PACK)))));

        List<ItemSummary> summaries = listItems.forList(MEMBER_SUB, householdId.toString(), listId.toString());

        assertThat(summaries)
                .containsExactly(
                        new ItemSummary(milchId.toString(), "Milch", "Bio", "1", "PIECE"),
                        new ItemSummary(brotId.toString(), "Brot", null, "2", "PACK"));
    }

    @Test
    void forList_returnsEmptyWhenTheListHasNoItems() {
        seedMembership();
        ListItems listItems = listItemsReading(new FakeItemReadModel((household, list) -> List.of()));

        assertThat(listItems.forList(MEMBER_SUB, householdId.toString(), listId.toString())).isEmpty();
    }

    @Test
    void forList_returnsEmptyForAListOwnedByAnotherHousehold() {
        seedMembership();
        HouseholdId otherHousehold = HouseholdId.generate();
        // The read model enforces the (household_id, list_id) filter — the item exists only under the
        // OTHER household, so a member querying THEIR own household for the same list id sees nothing
        // (AC8 no-data-leak: the query threads householdId into itemsOf, mirroring the SQL WHERE).
        ItemReadModel itemReadModel = new FakeItemReadModel((household, list) -> household.equals(otherHousehold)
                ? List.of(new ItemView(ItemId.generate(), new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE)))
                : List.of());

        List<ItemSummary> summaries =
                listItemsReading(itemReadModel).forList(MEMBER_SUB, householdId.toString(), listId.toString());

        assertThat(summaries).isEmpty();
    }

    @Test
    void forList_rejectsANonMemberWith403() {
        ListItems listItems = listItemsReading(new FakeItemReadModel((household, list) -> List.of()));

        assertThatThrownBy(() -> listItems.forList("stranger-sub", householdId.toString(), listId.toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void forList_mapsAMalformedListIdToListIdInvalid() {
        seedMembership();
        ListItems listItems = listItemsReading(new FakeItemReadModel((household, list) -> List.of()));

        assertThatThrownBy(() -> listItems.forList(MEMBER_SUB, householdId.toString(), "not-a-uuid"))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.listIdInvalid"));
    }
}
