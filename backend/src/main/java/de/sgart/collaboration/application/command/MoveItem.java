package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The caller's intention to move an item from one shopping list to another (Story 2.4, AC1). Like
 * {@link AddItem}, {@code basedOnVersion} is the <em>loaded</em> <strong>source</strong> list's
 * stream version the handler read before appending — an online load-then-append (AD-8). The
 * process-manager-issued {@code AddItem} on the target is a separate append the handler never
 * makes (AD-10).
 */
public record MoveItem(
        ShoppingListId sourceListId,
        ItemId itemId,
        ShoppingListId targetListId,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public MoveItem {
        Objects.requireNonNull(sourceListId, "sourceListId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(targetListId, "targetListId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
