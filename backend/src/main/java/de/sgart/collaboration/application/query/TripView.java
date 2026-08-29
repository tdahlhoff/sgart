package de.sgart.collaboration.application.query;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.TripNotFoundException;
import de.sgart.collaboration.domain.readmodel.ItemReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.collaboration.domain.readmodel.TripStoreReadModel;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.TripId;
import java.util.List;
import java.util.Objects;

/**
 * The store-grouped active-trip view (AC1, Cl. 4): composes the ACL with three read models — the
 * list's {@code active_trip_id} (the navigation key), the trip's stores ({@link
 * TripStoreReadModel}), and the list's items ({@link ItemReadModel}) — mirroring {@link ListItems}
 * composing across read models. A pure query — no side effects (CLAUDE.md §6 CQRS coverage).
 * Grouping items by store is deliberately the <strong>client's</strong> job (Cl. 7) — this returns
 * the flat store set + items.
 */
public final class TripView {

    private final ResolveMemberIdentity resolveMemberIdentity;
    private final ShoppingListReadModel shoppingListReadModel;
    private final TripStoreReadModel tripStoreReadModel;
    private final ItemReadModel itemReadModel;

    public TripView(
            ResolveMemberIdentity resolveMemberIdentity,
            ShoppingListReadModel shoppingListReadModel,
            TripStoreReadModel tripStoreReadModel,
            ItemReadModel itemReadModel) {
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.shoppingListReadModel =
                Objects.requireNonNull(shoppingListReadModel, "shoppingListReadModel must not be null");
        this.tripStoreReadModel = Objects.requireNonNull(tripStoreReadModel, "tripStoreReadModel must not be null");
        this.itemReadModel = Objects.requireNonNull(itemReadModel, "itemReadModel must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if {@code rawHouseholdId}/{@code rawListId} are missing or not a UUID (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws TripNotFoundException if the list is unknown, belongs to another household, or has no active trip (404)
     */
    public TripViewResult forList(String keycloakUserId, String rawHouseholdId, String rawListId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        ShoppingListId listId = CommandFieldTranslations.toShoppingListId(rawListId);

        // Only a member may view a trip — a non-member is a 403 (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        ShoppingListView list = shoppingListReadModel.listsOf(householdId).stream()
                .filter(candidate -> candidate.listId().equals(listId))
                .findFirst()
                .orElseThrow(() -> new TripNotFoundException("No active trip for list " + listId));
        TripId tripId = list.activeTripId();
        if (tripId == null) {
            throw new TripNotFoundException("No active trip for list " + listId);
        }

        List<String> storeIds =
                tripStoreReadModel.storesOf(tripId).stream().map(storeId -> storeId.value().toString()).toList();
        List<ListItems.ItemSummary> items = itemReadModel.itemsOf(householdId, listId).stream()
                .map(item -> new ListItems.ItemSummary(
                        item.itemId().toString(),
                        item.name().value(),
                        item.note() == null ? null : item.note().value(),
                        item.quantity().amount().toPlainString(),
                        item.quantity().unit().name(),
                        item.storeId() == null ? null : item.storeId().toString(),
                        item.status().name()))
                .toList();

        return new TripViewResult(tripId.toString(), listId.toString(), storeIds, items);
    }

    /**
     * The trip's grouped-view payload: the trip and list ids, the trip's stores in add order, and
     * the list's items — plain {@code String}s, not domain types, so {@code adapter.in} can consume
     * this record without reaching into {@code collaboration.domain}. Grouping items under a store
     * (and the „Noch nicht zugeordnet" fallback, Cl. 7) is the client's job.
     */
    public record TripViewResult(String tripId, String listId, List<String> storeIds, List<ListItems.ItemSummary> items) {}
}
