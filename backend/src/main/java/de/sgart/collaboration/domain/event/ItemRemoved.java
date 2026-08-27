package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * An item was removed from a shopping list (Story 2.3, AC4). Lives on the owning list's {@code
 * list-{id}} stream, like {@link ItemAdded} — {@code Item} never gets its own stream (AD-10).
 * Removing an unknown/already-removed item is a convergent no-op and raises nothing (AD-8,
 * mirroring {@link StoreArchived}'s already-archived branch). Carries no personal data — an id
 * only (AD-5/AD-6).
 */
public record ItemRemoved(EventId eventId, ShoppingListId listId, ItemId itemId) implements DomainEvent {

    public ItemRemoved {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
    }
}
