package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/** An Admin's intention to remove another member from the household (Story 4.3, AC4). */
public record RemoveMember(
        HouseholdId householdId, MemberId targetMemberId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public RemoveMember {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(targetMemberId, "targetMemberId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
