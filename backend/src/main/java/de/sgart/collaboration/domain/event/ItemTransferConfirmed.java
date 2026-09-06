package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The saga step that completes a transfer on the source (Story 3.6, AC2) — raised by {@link
 * ShoppingList#confirmItemTransfer} on the <em>source</em> list's stream once the {@code
 * ItemTransferProcessManager} has successfully added the item to the target (or found it already
 * there, a converged duplicate). Folds to a <strong>removal</strong> on the source — this is the
 * old eager removal {@code ItemMovedToList}/{@code ItemPostponedToList} used to do immediately,
 * now deferred until the target add is confirmed. A system-issued event — no member identity, no
 * personal data.
 */
public record ItemTransferConfirmed(EventId eventId, ShoppingListId listId, ItemId itemId) implements DomainEvent {

    public ItemTransferConfirmed {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
    }
}
