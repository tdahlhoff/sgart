package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import java.util.Objects;

/**
 * The caller's intention to discard an item during a trip (Story 3.4, AC2, Cl. 12) — the item
 * transitions to {@code DISCARDED} and stays on the list, dimmed. {@code basedOnVersion} is the
 * loaded list-stream version (AD-8). Mirrors {@link CheckOffItem}.
 */
public record DiscardItem(ShoppingListId listId, ItemId itemId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public DiscardItem {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
