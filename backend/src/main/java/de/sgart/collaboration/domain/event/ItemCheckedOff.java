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
 * An item was checked off — its status transitions to {@link ItemStatus#DONE} (Story 3.3, AC2,
 * Cl. 1) — raised on the list's own {@code list-{id}} stream by {@link
 * ShoppingList#checkOffItem}, gated on the list being {@code IN_TRIP}. Checking an
 * already-{@code DONE} item is a convergent no-op (raises nothing, AD-8). This is the only place
 * an item reaches {@code DONE} — a {@code DONE} item always has a trip context.
 *
 * <p>Carries no personal data and no <em>who</em> (AD-5/AD-6, mirrors {@link ItemRerouted}) — a
 * household/list/item id, never a person.
 */
public record ItemCheckedOff(
        EventId eventId, HouseholdId householdId, ShoppingListId listId, ItemId itemId)
        implements DomainEvent {

    public ItemCheckedOff {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
    }
}
