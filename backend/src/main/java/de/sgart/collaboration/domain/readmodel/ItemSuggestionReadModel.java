package de.sgart.collaboration.domain.readmodel;

import de.sgart.shared.HouseholdId;
import java.util.List;

/**
 * Domain-owned port over the item suggestion CQRS read model (AD-4, Story 2.5) — built solely by
 * {@code ShoppingListReadModelProjector} folding {@code ItemAdded}/{@code ItemUpdated}; a command
 * handler never writes it. {@code ListItemSuggestions} (application layer) is the query that reads
 * through this port. Unlike {@link ItemReadModel}, this read model is history-surviving: {@code
 * ItemRemoved}/{@code ItemTransferConfirmed} never remove a row (Cl. 1). {@code recordDefaultStore} (Story
 * 2.6, AC6) is the only writer of {@code default_store_id} — {@code recordUsage} never touches it
 * (Cl. 7).
 */
public interface ItemSuggestionReadModel {

    /**
     * @return the household's suggestion set — one entry per distinct previously-used item name,
     *     alphabetical by display name (Cl. 6). Deterministic across a full re-projection (no
     *     timestamp column decides the order), though the exact collation of "alphabetical" is the
     *     storage adapter's; the client re-sorts case-insensitively for its own panel.
     */
    List<ItemSuggestionView> suggestionsOf(HouseholdId householdId);
}
