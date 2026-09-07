package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import java.util.Objects;

/**
 * The caller's intention to leave their household (Story 4.3, AC3). {@code basedOnVersion} is the
 * loaded stream version (online load-then-append, mirroring {@link RenameHousehold}).
 */
public record LeaveHousehold(HouseholdId householdId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public LeaveHousehold {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
