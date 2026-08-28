package de.sgart.collaboration.application.query;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.readmodel.ItemReadModel;
import de.sgart.collaboration.domain.readmodel.ItemView;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import java.util.List;
import java.util.Objects;

/**
 * The read side of item management (AC6): a list's items, in creation order. A pure query — no
 * side effects (CLAUDE.md §6 CQRS coverage) — composing the Identity ACL's published {@link
 * ResolveMemberIdentity} port (AD-2, confirming the caller is a member) with the item read model
 * (AD-4). A {@code listId} under a different household yields an empty list — the read model is
 * queried by {@code (household_id, list_id)}, so there is no data leak (mirrors {@link
 * ListOpenLists}'s membership gate, but without a 404 — an empty list is indistinguishable from an
 * unknown one on the read side).
 */
public final class ListItems {

    private final ResolveMemberIdentity resolveMemberIdentity;
    private final ItemReadModel itemReadModel;

    public ListItems(ResolveMemberIdentity resolveMemberIdentity, ItemReadModel itemReadModel) {
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.itemReadModel = Objects.requireNonNull(itemReadModel, "itemReadModel must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if {@code rawHouseholdId}/{@code rawListId} are missing or not a UUID (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     */
    public List<ItemSummary> forList(String keycloakUserId, String rawHouseholdId, String rawListId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        ShoppingListId listId = CommandFieldTranslations.toShoppingListId(rawListId);

        // Only a member may list a list's items — a non-member is a 403 (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        return itemReadModel.itemsOf(householdId, listId).stream().map(ListItems::toSummary).toList();
    }

    private static ItemSummary toSummary(ItemView item) {
        ItemNote note = item.note();
        return new ItemSummary(
                item.itemId().toString(),
                item.name().value(),
                note == null ? null : note.value(),
                item.quantity().amount().toPlainString(),
                item.quantity().unit().name(),
                item.storeId() == null ? null : item.storeId().toString());
    }

    /**
     * An item as seen by the caller: id + name + optional note + quantity + assigned store — the
     * shape the list detail screen needs. Plain {@code String}s, not domain types, so {@code
     * adapter.in} can consume this record without reaching into {@code collaboration.domain}
     * (mirrors {@link ListOpenLists.ShoppingListSummary}). {@code note}/{@code storeId} are {@code
     * null} when absent; {@code amount} is a decimal string, {@code unit} the enum name.
     */
    public record ItemSummary(String itemId, String name, String note, String amount, String unit, String storeId) {}
}
