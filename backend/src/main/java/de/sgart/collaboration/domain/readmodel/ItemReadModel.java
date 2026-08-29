package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemStatus;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import java.util.List;
import java.util.Optional;

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

    /**
     * @return the household owning {@code itemId}, if its row has been projected yet (Story 2.5,
     *     Cl. 5) — used by the projector to resolve {@code ItemUpdated}'s missing {@code
     *     householdId} when recording a suggestion. Empty on an out-of-order/replay edge (the
     *     item's {@code ItemAdded} row not yet projected); the caller skips that suggestion and a
     *     later full replay recovers it. Deliberately not a {@code default} returning {@code
     *     Optional.empty()}: an implementation that forgot to answer it would silently stop
     *     recording last-used attributes (AC6) with no compile-time or runtime signal (Fail Fast).
     */
    Optional<HouseholdId> householdIdOf(ItemId itemId);

    /** The projector's {@code ItemAssignedToStore} write — sets the item's assigned store. */
    void assignStore(ItemId itemId, StoreId storeId);

    /** The projector's status-event write (Story 3.3) — sets the item's in-trip status. */
    void setStatus(ItemId itemId, ItemStatus status);

    /**
     * @return the item's name, if its row has been projected yet (Story 2.6, Cl. 6) — used by the
     *     projector to resolve the name {@code ItemAssignedToStore} does not carry, so it can record
     *     the suggestion's default store. Empty on an out-of-order/replay edge; the caller skips
     *     that suggestion write and a later full replay recovers it. Deliberately not a {@code
     *     default} returning {@code Optional.empty()} — same Fail-Fast reasoning as {@link
     *     #householdIdOf}.
     */
    Optional<ItemName> nameOf(ItemId itemId);
}
