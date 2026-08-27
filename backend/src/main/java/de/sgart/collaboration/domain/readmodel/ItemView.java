package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;

/**
 * An item as held in the read model (AD-4) — id, name, optional note, and quantity, in creation
 * order (Story 2.3). {@code note} is nullable: an item may carry no note. Read-only projection
 * shape, distinct from the aggregate's internal state.
 */
public record ItemView(ItemId itemId, ItemName name, ItemNote note, Quantity quantity) {}
