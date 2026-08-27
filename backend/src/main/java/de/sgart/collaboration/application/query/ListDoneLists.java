package de.sgart.collaboration.application.query;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.query.ListOpenLists.ShoppingListSummary;
import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Objects;

/**
 * The read side of the „Erledigt" archive (AC2): the household's Done lists, in creation order (the
 * read model has no completion timestamp yet — Epic 3 note). A pure query — no side effects
 * (CLAUDE.md §6 CQRS coverage) — mirroring {@link ListOpenLists} but filtering to {@link
 * ListStatus#DONE}. Reuses {@link ListOpenLists.ShoppingListSummary}; the shape is identical, so
 * duplicating it would violate DRY.
 */
public final class ListDoneLists {

    private final ResolveMemberIdentity resolveMemberIdentity;
    private final ShoppingListReadModel shoppingListReadModel;

    public ListDoneLists(ResolveMemberIdentity resolveMemberIdentity, ShoppingListReadModel shoppingListReadModel) {
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

        // Only a member may read a household's archive — a non-member is a 403 (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        return shoppingListReadModel.listsOf(householdId).stream()
                .filter(list -> list.status() == ListStatus.DONE)
                .map(ListDoneLists::toSummary)
                .toList();
    }

    private static ShoppingListSummary toSummary(ShoppingListView list) {
        return new ShoppingListSummary(
                list.listId().toString(),
                list.name() == null ? null : list.name().value(),
                list.status().name(),
                list.itemCount());
    }
}
