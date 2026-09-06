package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.TransferCancellationReason;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The compensating saga step that undoes a reservation on the source (Story 3.6, AC3 — the bug
 * fix) — raised by {@link ShoppingList#cancelItemTransfer} on the <em>source</em> list's stream
 * when the {@code ItemTransferProcessManager} finds the target not {@code OPEN} or gone. Folds to
 * an <strong>un-reserve</strong> on the source — the item returns to its normal (non-pending)
 * state, so it is never dropped: at no instant is it on neither list. A system-issued event — no
 * member identity, no personal data.
 */
public record ItemTransferCancelled(
        EventId eventId, ShoppingListId listId, ItemId itemId, TransferCancellationReason reason)
        implements DomainEvent {

    public ItemTransferCancelled {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
