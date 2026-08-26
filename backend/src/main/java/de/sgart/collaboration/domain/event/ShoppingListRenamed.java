package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * A shopping list was renamed (Story 2.1, AC3). Carries the list's id and its new name only — never
 * <em>who</em> renamed it: a rename is not personal data, and MVP tracks no rename audit trail
 * (AD-5/AD-6, YAGNI; mirroring {@link HouseholdRenamed}). The {@code OPEN}-only guard is enforced by
 * {@link ShoppingList#rename} before this event is ever raised, not recorded on the event itself.
 */
public record ShoppingListRenamed(EventId eventId, ShoppingListId listId, ShoppingListName newName)
        implements DomainEvent {

    public ShoppingListRenamed {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(newName, "newName must not be null");
    }
}
