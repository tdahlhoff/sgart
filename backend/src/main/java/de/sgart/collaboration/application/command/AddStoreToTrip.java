package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import java.util.Objects;

/**
 * The caller's intention to add a store to an active trip spontaneously (Story 3.2, AC3) — the
 * trip's first in-trip mutation. {@code basedOnVersion} is the <em>loaded</em> trip-stream version
 * the handler read before appending — an online load-then-append (AD-8, mirrors {@link StartTrip}).
 */
public record AddStoreToTrip(TripId tripId, StoreId storeId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public AddStoreToTrip {
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
