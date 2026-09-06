package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.TransferOrigin;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * An item transfer to another list was initiated (Story 3.6, AC1) — raised on the <em>source</em>
 * list's {@code list-{id}} stream by either {@link ShoppingList#moveItem} ({@code
 * TransferOrigin#PLANNING_MOVE}, {@code OPEN}-gated) or {@link ShoppingList#postponeItemToList}
 * ({@code TransferOrigin#IN_TRIP_POSTPONE}, {@code IN_TRIP}-gated) — the two phases share one saga
 * vocabulary (decision 1), unifying the retired {@code ItemMovedToList} and {@code
 * ItemPostponedToList}. Unlike those, this folds to a <strong>reserved</strong> sub-state on the
 * source — the item <strong>stays</strong> on the source, it is not removed. The {@code
 * ItemTransferProcessManager} reacts by adding the item to the target and then issuing either
 * {@link ItemTransferConfirmed} (success) or {@link ItemTransferCancelled} (target not {@code
 * OPEN}/gone) back on the source.
 *
 * <p>Carries the full item payload ({@code name}, {@code note}, {@code quantity}) so the process
 * manager never has to read the source aggregate again to know what to add. {@code note} is
 * intentionally nullable — an item may carry no note. Carries no personal data — ids and item
 * content only, never a person (AD-5/AD-6).
 */
public record ItemTransferInitiated(
        EventId eventId,
        HouseholdId householdId,
        ShoppingListId sourceListId,
        ItemId itemId,
        ShoppingListId targetListId,
        ItemName name,
        ItemNote note,
        Quantity quantity,
        TransferOrigin origin)
        implements DomainEvent {

    public ItemTransferInitiated {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(sourceListId, "sourceListId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(targetListId, "targetListId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        // note is intentionally nullable — an item may carry no note.
    }
}
