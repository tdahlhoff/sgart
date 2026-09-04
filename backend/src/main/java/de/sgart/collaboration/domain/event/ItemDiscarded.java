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
 * An item was discarded — its status transitions to {@link ItemStatus#DISCARDED} (Story 3.4, AC2,
 * Cl. 1/12) — raised on the list's own {@code list-{id}} stream by either the explicit {@link
 * ShoppingList#discardItem} (the first-class in-trip "Verwerfen" action, Cl. 12) or the {@link
 * ShoppingList#completeTrip} sweep (the quality-of-life safety net that discards any item still
 * {@code OPEN} when the trip is completed, Cl. 2). Gated on the list being {@code IN_TRIP}. The
 * item <strong>stays on the list</strong>, dimmed with a "Verworfen" treatment — this is
 * <strong>not</strong> a removal (distinct from {@link ItemPostponedToList} or {@link ItemRemoved}).
 * A {@code DISCARDED} item is excluded from the "remaining open" set and the progress count N, but
 * counted in the total M. Already-{@code DISCARDED} is a convergent no-op (raises nothing, AD-8).
 * {@link ItemUnchecked} returns a {@code DISCARDED} item to {@code OPEN}.
 *
 * <p>Carries no personal data and no <em>who</em> (AD-5/AD-6, mirrors {@link ItemCheckedOff}) — a
 * household/list/item id, never a person.
 */
public record ItemDiscarded(
        EventId eventId, HouseholdId householdId, ShoppingListId listId, ItemId itemId)
        implements DomainEvent {

    public ItemDiscarded {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
    }
}
