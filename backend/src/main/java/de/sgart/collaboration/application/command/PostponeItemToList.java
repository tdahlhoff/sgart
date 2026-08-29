package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The caller's intention to postpone an item from the current trip list to another open list
 * (Story 3.3, AC4/AC5). Like {@link MoveItem}, {@code basedOnVersion} is the loaded
 * <strong>source</strong> list's stream version — online load-then-append (AD-8). The
 * process-manager-issued {@code AddItem} on the target is a separate append (AD-10).
 */
public record PostponeItemToList(
        ShoppingListId sourceListId,
        ItemId itemId,
        ShoppingListId targetListId,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public PostponeItemToList {
        Objects.requireNonNull(sourceListId, "sourceListId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(targetListId, "targetListId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
