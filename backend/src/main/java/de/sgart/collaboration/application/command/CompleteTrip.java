package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.TripId;
import java.util.Objects;

/**
 * The caller's intention to complete a trip (Story 3.4, AC4, Cl. 7). {@code tripId} is validated
 * for envelope completeness and REST clarity but the handler resolves the command via the
 * <em>list</em> (completion is a {@code ShoppingList} command — {@code tripId} is informational,
 * mirroring add-store's loose {@code {tripId}}). {@code basedOnVersion} is the loaded list-stream
 * version (AD-8). Mirrors {@link StartTrip}.
 */
public record CompleteTrip(
        ShoppingListId listId,
        TripId tripId,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public CompleteTrip {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
