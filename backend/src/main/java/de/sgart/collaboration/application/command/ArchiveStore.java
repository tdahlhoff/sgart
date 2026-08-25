package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StoreId;
import java.util.Objects;

/**
 * The caller's intention to archive (soft-remove) a store from a household (Story 1.8, AC3). Like
 * {@link AddStore}, {@code basedOnVersion} is the <em>loaded</em> household-stream version the
 * handler read before appending — an online load-then-append (AD-8).
 */
public record ArchiveStore(
        HouseholdId householdId, StoreId storeId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public ArchiveStore {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
