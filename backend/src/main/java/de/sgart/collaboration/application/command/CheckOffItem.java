package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The caller's intention to check off an item during a trip (Story 3.3, AC1) — the item transitions
 * to {@code DONE}. {@code basedOnVersion} is the loaded list-stream version (AD-8).
 */
public record CheckOffItem(ShoppingListId listId, ItemId itemId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public CheckOffItem {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
