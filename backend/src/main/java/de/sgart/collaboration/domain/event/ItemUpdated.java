package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * An item's name, note, and/or quantity were changed (Story 2.3, AC3). Lives on the owning list's
 * {@code list-{id}} stream, like {@link ItemAdded} — {@code Item} never gets its own stream
 * (AD-10). Raised only when at least one field actually changes; an update that leaves everything
 * unchanged is a convergent no-op and raises nothing (AD-8, mirroring {@link ShoppingListRenamed}).
 *
 * <p>{@code note} is intentionally nullable — an updated item may carry no note. Carries no
 * personal data — content only, never a person (AD-5/AD-6, no audit trail, YAGNI).
 */
public record ItemUpdated(
        EventId eventId, ShoppingListId listId, ItemId itemId, ItemName name, ItemNote note, Quantity quantity)
        implements DomainEvent {

    public ItemUpdated {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        // note is intentionally nullable — an updated item may carry no note.
    }
}
