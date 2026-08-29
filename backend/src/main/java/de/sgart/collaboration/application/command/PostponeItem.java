package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The caller's intention to postpone an item in-place during a trip (Story 3.3, AC3) — the item
 * stays on the same list but transitions to {@code POSTPONED}. {@code basedOnVersion} is the loaded
 * list-stream version (AD-8).
 */
public record PostponeItem(ShoppingListId listId, ItemId itemId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public PostponeItem {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
