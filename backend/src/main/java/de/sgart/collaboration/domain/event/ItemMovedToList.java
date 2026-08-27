package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * An item was moved from one shopping list to another while planning (Story 2.4, AC1). Lives on
 * the <em>source</em> list's {@code list-{id}} stream — {@code Item} never gets its own stream
 * (AD-10) — and folds to a removal there, exactly like {@link ItemRemoved}. The target-side add is
 * a separate {@link ItemAdded}, raised on the target's own stream by the {@code
 * ItemMoveProcessManager} reacting to this event (AD-10, SGART's first process manager).
 *
 * <p>Carries the full item payload ({@code name}, {@code note}, {@code quantity}) so the process
 * manager never has to read the source aggregate again to know what to add. {@code note} is
 * intentionally nullable — an item may carry no note (AC1/AC9). Carries no personal data — ids and
 * item content only, never a person (AD-5/AD-6; no audit trail in MVP, YAGNI, mirroring {@link
 * ItemAdded}). {@code householdId} is carried so the {@code item_read_model} can denormalise it
 * for Epic-6 erasure locability.
 */
public record ItemMovedToList(
        EventId eventId,
        HouseholdId householdId,
        ShoppingListId sourceListId,
        ItemId itemId,
        ShoppingListId targetListId,
        ItemName name,
        ItemNote note,
        Quantity quantity)
        implements DomainEvent {

    public ItemMovedToList {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(sourceListId, "sourceListId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(targetListId, "targetListId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        // note is intentionally nullable — an item may carry no note (AC1/AC9).
    }
}
