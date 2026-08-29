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
 * An item was postponed onto another list <em>during</em> a trip (Story 3.3, AC4, Cl. 3) — raised
 * on the <em>source</em> list's {@code list-{id}} stream by {@link ShoppingList#postponeItemToList},
 * gated on the list being {@code IN_TRIP}. Distinct from the {@code OPEN}-gated {@link
 * ItemMovedToList} so the write-side gate for each phase stays unambiguous (the reroute-vs-assign
 * precedent, Story 3.2 Cl. 1). Folds to a removal on the source list (exactly like {@link
 * ItemMovedToList}); the target-side add is a separate {@code ItemAdded} raised on the target's own
 * stream by the {@code ItemMoveProcessManager} reacting to this event (AD-10).
 *
 * <p>Carries the full item payload ({@code name}, {@code note}, {@code quantity}) so the process
 * manager never has to read the source aggregate again. {@code note} is intentionally nullable — an
 * item may carry no note. Carries no personal data — ids and item content only, never a person
 * (AD-5/AD-6, mirrors {@link ItemMovedToList}).
 */
public record ItemPostponedToList(
        EventId eventId,
        HouseholdId householdId,
        ShoppingListId sourceListId,
        ItemId itemId,
        ShoppingListId targetListId,
        ItemName name,
        ItemNote note,
        Quantity quantity)
        implements DomainEvent {

    public ItemPostponedToList {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(sourceListId, "sourceListId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(targetListId, "targetListId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        // note is intentionally nullable — an item may carry no note.
    }
}
