package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * A shopping list was created (Story 2.1, AC1). {@link ShoppingList} is the second real aggregate
 * in the Collaboration context and a distinct aggregate from {@code Household} (AD-3) — this event
 * lives on its own {@code list-{id}} stream, never the household stream, and carries the
 * household's id only, never the household object.
 *
 * <p>{@code name} is intentionally nullable — a blank/absent caller-supplied name creates a valid
 * unnamed list (AC1/AC2), not an error, mirroring {@link StoreAdded}'s nullable {@code chainId}
 * convention. Carries no personal data — a household/list id and an optional list name, never a
 * person (AD-5/AD-6); the event does not record <em>who</em> created the list (no audit trail in
 * MVP, YAGNI, mirroring {@link HouseholdRenamed}).
 */
public record ShoppingListCreated(
        EventId eventId, HouseholdId householdId, ShoppingListId listId, ShoppingListName name)
        implements DomainEvent {

    public ShoppingListCreated {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        // name is intentionally nullable — an unnamed list has no display name (AC1/AC2).
    }
}
