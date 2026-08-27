package de.sgart.collaboration.application.command;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The caller's intention to update an existing item's name, note, and/or quantity (Story 2.3, AC3).
 * Like {@link AddItem}, {@code basedOnVersion} is the <em>loaded</em> list-stream version the
 * handler read before appending — an online load-then-append (AD-8).
 */
public record UpdateItem(
        ShoppingListId listId,
        ItemId itemId,
        ItemName name,
        ItemNote note,
        Quantity quantity,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public UpdateItem {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
        // note is intentionally nullable — an item may carry no note.
    }
}
