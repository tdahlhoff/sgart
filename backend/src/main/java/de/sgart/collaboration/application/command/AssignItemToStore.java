package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import java.util.Objects;

/**
 * The caller's intention to assign an item to a store while planning (Story 2.6, AC1). Like {@link
 * UpdateItem}, {@code basedOnVersion} is the <em>loaded</em> list-stream version the handler read
 * before appending — an online load-then-append (AD-8). {@code storeId} is not validated against
 * the household's stores here — the aggregate does not load {@code Household} (Cl. 1).
 */
public record AssignItemToStore(
        ShoppingListId listId,
        ItemId itemId,
        StoreId storeId,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public AssignItemToStore {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
