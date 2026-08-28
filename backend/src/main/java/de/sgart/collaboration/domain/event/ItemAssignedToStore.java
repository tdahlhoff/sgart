package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import java.util.Objects;

/**
 * An item was assigned to a store while planning (Story 2.6, AC1). {@code Item} is an entity
 * <em>inside</em> the {@link ShoppingList} aggregate (AD-10), so this event lives on the list's
 * own {@code list-{id}} stream — {@code Store} is a separate aggregate's entity (inside {@code
 * Household}) this event only references by id (AD-3), never validates.
 *
 * <p>Carries {@code householdId} so the projector can record the suggestion default store without
 * a lookup (only the item's name is looked up, Cl. 6). Carries no personal data — a
 * household/list/item/store id, never a person (AD-5/AD-6); records no <em>who</em> assigned it
 * (no audit trail in MVP, YAGNI, mirroring {@link ItemAdded}). {@code storeId} is required — there
 * is no "unassign" event in MVP (Cl. 3, reassign-only).
 */
public record ItemAssignedToStore(
        EventId eventId, HouseholdId householdId, ShoppingListId listId, ItemId itemId, StoreId storeId)
        implements DomainEvent {

    public ItemAssignedToStore {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
    }
}
