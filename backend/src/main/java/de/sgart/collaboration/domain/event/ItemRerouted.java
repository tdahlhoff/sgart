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
 * An item was re-routed to a store <em>during</em> a trip (Story 3.2, AC2, Cl. 1) — raised on the
 * list's own {@code list-{id}} stream by {@link ShoppingList#rerouteItem}, gated on the list being
 * {@code IN_TRIP}. Distinct from {@link ItemAssignedToStore} (planning-time, {@code OPEN}-gated)
 * so the write-side gate for each phase stays unambiguous, but the read side converges on the
 * <strong>same</strong> {@code item_read_model.store_id} — one source of truth for item→store
 * (Cl. 1). Reroute never touches the item's status; it is distinct from Postpone (Story 3.3).
 *
 * <p>Carries no personal data and no <em>who</em> (AD-5/AD-6, mirrors {@link ItemAssignedToStore})
 * — a household/list/item/store id, never a person.
 */
public record ItemRerouted(
        EventId eventId, HouseholdId householdId, ShoppingListId listId, ItemId itemId, StoreId storeId)
        implements DomainEvent {

    public ItemRerouted {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
    }
}
