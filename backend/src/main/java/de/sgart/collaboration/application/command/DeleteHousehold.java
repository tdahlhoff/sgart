package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import java.util.Objects;

/** An Admin's intention to delete the household (Story 4.3, AC7). */
public record DeleteHousehold(HouseholdId householdId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public DeleteHousehold {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
