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
 * An item was added to a shopping list (Story 2.3, AC1). {@code Item} is an entity <em>inside</em>
 * the {@link ShoppingList} aggregate (AD-10), so this event lives on the list's own {@code
 * list-{id}} stream — there is no separate item stream, mirroring {@link StoreAdded}'s placement on
 * the household stream.
 *
 * <p>{@code note} is intentionally nullable — an item may carry no note (AC1/AC2). Carries no
 * personal data — a household/list/item id, the item's name/note/quantity, never a person
 * (AD-5/AD-6); the event does not record <em>who</em> added the item (no audit trail in MVP, YAGNI,
 * mirroring {@link ShoppingListCreated}). {@code householdId} is carried so the {@code
 * item_read_model} can denormalise it for Epic-6 erasure locability.
 */
public record ItemAdded(
        EventId eventId,
        HouseholdId householdId,
        ShoppingListId listId,
        ItemId itemId,
        ItemName name,
        ItemNote note,
        Quantity quantity)
        implements DomainEvent {

    public ItemAdded {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        // note is intentionally nullable — an item may carry no note (AC1/AC2).
    }
}
