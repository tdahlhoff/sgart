package de.sgart.collaboration.application.command;

import de.sgart.collaboration.domain.StoreName;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import java.util.Objects;

/**
 * The caller's intention to add a store to a household (Story 1.8, AC1). Like {@link
 * RenameHousehold}, {@code basedOnVersion} is the <em>loaded</em> household-stream version the
 * handler read before appending — an online load-then-append (AD-8); a client-supplied
 * {@code basedOnVersion} plus the offline queue is Epic 5.
 *
 * <p>The {@link StoreId} is minted <em>client-side</em> and carried here, so the command response
 * needs no body (read-your-writes without waiting on a projection). {@code chainId} is the optional
 * accepted chain suggestion (AC2), {@code null} when the store is unlinked.
 */
public record AddStore(
        HouseholdId householdId,
        StoreId storeId,
        StoreName name,
        StoreChainId chainId,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public AddStore {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
        // chainId is intentionally nullable — an unlinked store has no accepted chain (AC2).
    }
}
