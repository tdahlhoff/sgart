package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.StoreId;

/**
 * An item as held in the read model (AD-4) — id, name, optional note, quantity, and assigned
 * store, in creation order (Story 2.3, Story 2.6). {@code note} and {@code storeId} are nullable:
 * an item may carry no note and may be unassigned. Read-only projection shape, distinct from the
 * aggregate's internal state.
 */
public record ItemView(ItemId itemId, ItemName name, ItemNote note, Quantity quantity, StoreId storeId) {}
