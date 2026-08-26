package de.sgart.collaboration.application.query;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Objects;

/**
 * The read side of list management (AC1, AC2, AC4): the household's Open lists, in creation order.
 * A pure query — no side effects (CLAUDE.md §6 CQRS coverage) — composing the Identity ACL's
 * published {@link ResolveMemberIdentity} port (AD-2, confirming the caller is a member) with the
 * list read model. Returns lists in creation order so the client can derive the AC2 "Liste N"
 * ordinal from the array position — the ordinal itself is never computed or stored here
 * (derivation lives on the client, Dev Notes).
 */
public final class ListOpenLists {

    private final ResolveMemberIdentity resolveMemberIdentity;
    private final ShoppingListReadModel shoppingListReadModel;

    public ListOpenLists(ResolveMemberIdentity resolveMemberIdentity, ShoppingListReadModel shoppingListReadModel) {
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.shoppingListReadModel =
                Objects.requireNonNull(shoppingListReadModel, "shoppingListReadModel must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if {@code rawHouseholdId} is missing or not a UUID (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     */
    public List<ShoppingListSummary> forHousehold(String keycloakUserId, String rawHouseholdId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);

        // Only a member may list a household's lists — a non-member is a 403 (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        return shoppingListReadModel.listsOf(householdId).stream()
                .filter(list -> list.status() == ListStatus.OPEN)
                .map(ListOpenLists::toSummary)
                .toList();
    }

    private static ShoppingListSummary toSummary(ShoppingListView list) {
        return new ShoppingListSummary(
                list.listId().toString(), list.name() == null ? null : list.name().value(), list.status().name());
    }

    /**
     * A list as seen by the caller: id + optional name + status, in creation order — the shape the
     * minimal lists surface needs. Plain {@code String}s, not domain types, so {@code adapter.in}
     * can consume this record without reaching into {@code collaboration.domain}. {@code name} is
     * {@code null} for an unnamed list.
     */
    public record ShoppingListSummary(String listId, String name, String status) {}
}
