package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ItemId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import java.util.Objects;

/**
 * The caller's intention to reroute an item to a different trip store <em>during</em> a trip
 * (Story 3.2, AC2, Cl. 1) — mirrors {@link AssignItemToStore} but for the {@code IN_TRIP} phase
 * (Cl. 8). {@code basedOnVersion} is the <em>loaded</em> list-stream version the handler read
 * before appending — an online load-then-append (AD-8). {@code storeId} is not validated against
 * the trip's stores here (Cl. 5).
 */
public record RerouteItem(
        ShoppingListId listId, ItemId itemId, StoreId storeId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public RerouteItem {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
