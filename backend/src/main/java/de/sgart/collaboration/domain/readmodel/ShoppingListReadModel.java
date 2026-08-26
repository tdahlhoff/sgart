package de.sgart.collaboration.domain.readmodel;

import de.sgart.shared.HouseholdId;
import java.util.List;

/**
 * Domain-owned port over the shopping-list CQRS read model (AD-4) — built solely by {@code
 * ShoppingListReadModelProjector} folding {@code ShoppingListCreated}/{@code ShoppingListRenamed};
 * a command handler never writes it. {@code ListOpenLists} (application layer) is the query that
 * reads through this port.
 *
 * <p>Exposes every list, not just {@code Open} ones — the AC2 ordinal counts a household's Open
 * (and later In-Trip) lists in creation order, so the query filters/derives from the full
 * creation-ordered sequence this port returns, and 2.2's Offen/Erledigt split reads through the
 * same port unchanged.
 */
public interface ShoppingListReadModel {

    /** @return the household's lists in creation order (oldest first), across every status. */
    List<ShoppingListView> listsOf(HouseholdId householdId);
}
