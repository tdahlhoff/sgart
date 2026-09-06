package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ItemStatus;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.StoreId;

/**
 * An item as held in the read model (AD-4) — id, name, optional note, quantity, assigned store,
 * in-trip status, and transfer-pending flag, in creation order (Story 2.3, Story 2.6, Story 3.3,
 * Story 3.6). {@code note} and {@code storeId} are nullable: an item may carry no note and may be
 * unassigned. {@code status} is always non-null (defaults to {@link ItemStatus#OPEN} at insert,
 * per V10). {@code transferPending} defaults to {@code false} at insert (per V11) and is set while
 * the two-phase transfer saga has the item reserved on this list. Read-only projection shape,
 * distinct from the aggregate's internal state.
 */
public record ItemView(
        ItemId itemId,
        ItemName name,
        ItemNote note,
        Quantity quantity,
        StoreId storeId,
        ItemStatus status,
        boolean transferPending) {}
