package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The caller's intention to remove an item from a shopping list (Story 2.3, AC4). Like {@link
 * AddItem}, {@code basedOnVersion} is the <em>loaded</em> list-stream version the handler read
 * before appending — an online load-then-append (AD-8).
 */
public record RemoveItem(ShoppingListId listId, ItemId itemId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public RemoveItem {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
