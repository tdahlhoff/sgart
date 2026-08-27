package de.sgart.collaboration.domain.readmodel;

import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import java.util.List;

/**
 * Domain-owned port over the item CQRS read model (AD-4) — built solely by {@code
 * ShoppingListReadModelProjector} folding {@code ItemAdded}/{@code ItemUpdated}/{@code
 * ItemRemoved}; a command handler never writes it. {@code ListItems} (application layer) is the
 * query that reads through this port.
 */
public interface ItemReadModel {

    /**
     * @return the list's items in creation order (oldest first), scoped to {@code householdId} —
     *     a {@code listId} under a different household yields an empty result (no data leak,
     *     mirroring the write side's cross-household defense-in-depth).
     */
    List<ItemView> itemsOf(HouseholdId householdId, ShoppingListId listId);
}
