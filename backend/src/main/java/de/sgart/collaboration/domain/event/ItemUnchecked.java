package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ItemStatus;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * An item was unchecked — its status returns to {@link ItemStatus#OPEN} (Story 3.3, AC2/AC3,
 * Cl. 1) — raised on the list's own {@code list-{id}} stream by {@link
 * ShoppingList#uncheckItem}, gated on the list being {@code IN_TRIP}. This is the undo affordance
 * for both {@code DONE} and {@code POSTPONED}: an unchecked item returns to {@code OPEN} from any
 * non-{@code OPEN} status. Unchecking an already-{@code OPEN} item is a convergent no-op (raises
 * nothing, AD-8).
 *
 * <p>Carries no personal data and no <em>who</em> (AD-5/AD-6, mirrors {@link ItemRerouted}) — a
 * household/list/item id, never a person.
 */
public record ItemUnchecked(
        EventId eventId, HouseholdId householdId, ShoppingListId listId, ItemId itemId)
        implements DomainEvent {

    public ItemUnchecked {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
    }
}
