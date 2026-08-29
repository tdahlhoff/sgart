package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The caller's intention to uncheck (reopen) a done or postponed item during a trip (Story 3.3,
 * AC2) — the item transitions to {@code OPEN}. {@code basedOnVersion} is the loaded list-stream
 * version (AD-8).
 */
public record UncheckItem(ShoppingListId listId, ItemId itemId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public UncheckItem {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
