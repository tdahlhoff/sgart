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
 * The caller's intention to add an item to a shopping list (Story 2.3, AC1). {@code basedOnVersion}
 * is the <em>loaded</em> list-stream version the handler read before appending — an online
 * load-then-append (AD-8). The {@link ItemId} is minted <em>client-side</em> and carried here, so
 * the command response needs no body (read-your-writes without waiting on a projection). {@code
 * note} is the optional item note, {@code null} when absent.
 */
public record AddItem(
        ShoppingListId listId,
        ItemId itemId,
        ItemName name,
        ItemNote note,
        Quantity quantity,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public AddItem {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
        // note is intentionally nullable — an item may carry no note (AC1/AC2).
    }
}
