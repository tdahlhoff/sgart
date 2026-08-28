package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import java.util.List;
import java.util.Objects;

/**
 * The caller's intention to start a trip against a list across one or more stores (Story 3.1,
 * AC1). {@code basedOnVersion} is the <em>loaded</em> list's stream version the handler read
 * before appending — an online load-then-append (AD-8). {@code tripId} is client-minted (mirrors
 * {@code listId} on {@link CreateShoppingList}) so the response needs no body.
 */
public record StartTrip(
        ShoppingListId listId,
        TripId tripId,
        List<StoreId> storeIds,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public StartTrip {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(storeIds, "storeIds must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
        storeIds = List.copyOf(storeIds);
    }
}
