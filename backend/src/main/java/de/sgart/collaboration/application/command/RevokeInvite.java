package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import java.util.Objects;

/** An Admin's intention to revoke a pending invite (Story 4.3, AC6). */
public record RevokeInvite(
        HouseholdId householdId, InviteId inviteId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public RevokeInvite {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
