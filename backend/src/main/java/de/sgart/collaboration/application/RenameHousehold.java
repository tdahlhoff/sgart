package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import java.util.Objects;

/**
 * The caller's intention to rename an existing household (Story 1.7, AC3). Unlike
 * {@link CreateHousehold}, {@code basedOnVersion} is the <em>loaded</em> stream version the handler
 * read before appending — this is an online load-then-append rename (Clarification D); a
 * client-supplied {@code basedOnVersion} plus the offline queue is Epic 5. A concurrent rename that
 * advanced the stream past this version loses with the store's {@code ConcurrencyConflictException}
 * (AD-8).
 */
public record RenameHousehold(
        HouseholdId householdId, HouseholdName newName, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public RenameHousehold {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(newName, "newName must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
