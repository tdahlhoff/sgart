package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.exception.TripNotFoundException;
import de.sgart.collaboration.application.query.ListItems;
import de.sgart.collaboration.application.query.TripView;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.readmodel.ItemReadModel;
import de.sgart.collaboration.domain.readmodel.ItemView;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.collaboration.domain.readmodel.TripStoreReadModel;
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
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import de.sgart.shared.Unit;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the trip query (AC1,
 * AC5): it resolves the list's active trip and returns its stores + items; rejects a non-member
 * (403); and a list with no active trip, an unknown list, or a cross-household list yields 404
 * (no data leak, mirroring {@code ListItemsTest}).
 */
class TripViewTest {

    private static final String MEMBER_SUB = "anna-sub";

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();

    private void seedMembership() {
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
    }

    private record FakeShoppingListReadModel(BiFunction<HouseholdId, ShoppingListId, ShoppingListView> lookup)
            implements ShoppingListReadModel {

        @Override
        public List<ShoppingListView> listsOf(HouseholdId householdId) {
            ShoppingListView view = lookup.apply(householdId, null);
            return view == null ? List.of() : List.of(view);
        }
    }

    private TripView tripView(ShoppingListReadModel shoppingListReadModel, TripStoreReadModel tripStoreReadModel,
            ItemReadModel itemReadModel) {
        return new TripView(
                new ResolveMemberIdentity(mappingRepository), shoppingListReadModel, tripStoreReadModel, itemReadModel);
    }

    private record FakeTripStoreReadModel(java.util.Map<TripId, List<StoreId>> storesByTrip)
            implements TripStoreReadModel {

        @Override
        public void addStore(TripId tripId, StoreId storeId) {
            throw new UnsupportedOperationException("the projector's write, never a query's");
        }

        @Override
        public List<StoreId> storesOf(TripId tripId) {
            return storesByTrip.getOrDefault(tripId, List.of());
        }
    }

    private record FakeItemReadModel(List<ItemView> items) implements ItemReadModel {
        @Override
        public List<ItemView> itemsOf(HouseholdId householdId, ShoppingListId listId) {
            return items;
        }

        @Override
        public Optional<HouseholdId> householdIdOf(ItemId itemId) {
            return Optional.empty();
        }

        @Override
        public void assignStore(ItemId itemId, StoreId storeId) {
            throw new UnsupportedOperationException("the projector's write, never a query's");
        }

        @Override
        public Optional<ItemName> nameOf(ItemId itemId) {
            return Optional.empty();
        }
    }

    @Test
    void forList_returnsTheTripsStoresAndTheListsItems() {
        seedMembership();
        TripId tripId = TripId.generate();
        StoreId edeka = StoreId.generate();
        StoreId netto = StoreId.generate();
        ItemId itemId = ItemId.generate();
        ShoppingListView list = new ShoppingListView(
                listId, new ShoppingListName("Wocheneinkauf"), ListStatus.IN_TRIP, 1, tripId);
        TripView tripView = tripView(
                new FakeShoppingListReadModel((household, id) -> household.equals(householdId) ? list : null),
                new FakeTripStoreReadModel(java.util.Map.of(tripId, List.of(edeka, netto))),
                new FakeItemReadModel(List.of(
                        new ItemView(itemId, new ItemName("Milch"), null, Quantity.of(1, Unit.PIECE), edeka))));

        TripView.TripViewResult result = tripView.forList(MEMBER_SUB, householdId.toString(), listId.toString());

        assertThat(result.tripId()).isEqualTo(tripId.toString());
        assertThat(result.listId()).isEqualTo(listId.toString());
        assertThat(result.storeIds()).containsExactly(edeka.toString(), netto.toString());
        assertThat(result.items())
                .containsExactly(new ListItems.ItemSummary(
                        itemId.toString(), "Milch", null, "1", "PIECE", edeka.toString()));
    }

    @Test
    void forList_rejectsANonMemberWith403() {
        TripView tripView = tripView(
                new FakeShoppingListReadModel((household, id) -> null),
                new FakeTripStoreReadModel(java.util.Map.of()),
                new FakeItemReadModel(List.of()));

        assertThatThrownBy(() -> tripView.forList("stranger-sub", householdId.toString(), listId.toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void forList_aListWithNoActiveTripIsNotFound() {
        seedMembership();
        ShoppingListView list =
                new ShoppingListView(listId, new ShoppingListName("Wocheneinkauf"), ListStatus.OPEN, 0, null);
        TripView tripView = tripView(
                new FakeShoppingListReadModel((household, id) -> household.equals(householdId) ? list : null),
                new FakeTripStoreReadModel(java.util.Map.of()),
                new FakeItemReadModel(List.of()));

        assertThatThrownBy(() -> tripView.forList(MEMBER_SUB, householdId.toString(), listId.toString()))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void forList_anUnknownListIsNotFound() {
        seedMembership();
        TripView tripView = tripView(
                new FakeShoppingListReadModel((household, id) -> null),
                new FakeTripStoreReadModel(java.util.Map.of()),
                new FakeItemReadModel(List.of()));

        assertThatThrownBy(() -> tripView.forList(MEMBER_SUB, householdId.toString(), listId.toString()))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void forList_aListInAnotherHouseholdIsNotFound() {
        seedMembership();
        HouseholdId otherHouseholdId = HouseholdId.generate();
        TripId tripId = TripId.generate();
        ShoppingListView listUnderOtherHousehold = new ShoppingListView(
                listId, new ShoppingListName("Wocheneinkauf"), ListStatus.IN_TRIP, 0, tripId);
        TripView tripView = tripView(
                new FakeShoppingListReadModel(
                        (household, id) -> household.equals(otherHouseholdId) ? listUnderOtherHousehold : null),
                new FakeTripStoreReadModel(java.util.Map.of()),
                new FakeItemReadModel(List.of()));

        assertThatThrownBy(() -> tripView.forList(MEMBER_SUB, householdId.toString(), listId.toString()))
                .isInstanceOf(TripNotFoundException.class);
    }
}
