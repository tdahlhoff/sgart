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
 * An item was postponed in place — its status transitions to {@link ItemStatus#POSTPONED} (Story
 * 3.3, AC3, Cl. 1) — raised on the list's own {@code list-{id}} stream by {@link
 * ShoppingList#postponeItemInPlace}, gated on the list being {@code IN_TRIP}. The item stays on
 * this list but is visibly set aside (the „couldn't get it, not moving it elsewhere" case).
 * Postponing an already-{@code POSTPONED} item is a convergent no-op (raises nothing, AD-8).
 * {@link ItemUnchecked} returns a {@code POSTPONED} item to {@code OPEN}.
 *
 * <p>Carries no personal data and no <em>who</em> (AD-5/AD-6, mirrors {@link ItemRerouted}) — a
 * household/list/item id, never a person.
 */
public record ItemPostponed(
        EventId eventId, HouseholdId householdId, ShoppingListId listId, ItemId itemId)
        implements DomainEvent {

    public ItemPostponed {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
    }
}
