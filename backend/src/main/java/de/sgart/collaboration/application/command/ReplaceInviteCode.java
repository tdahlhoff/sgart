package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import java.util.Objects;

/**
 * An Admin's intention to invalidate the household's active invite code and issue a fresh one in
 * its place (Story 8.4, F7). {@code newInviteId} is generated client-side, like {@link
 * CreateHousehold}'s first code — the response needs no body (read-your-writes); a retry with the
 * same {@code commandId} and {@code newInviteId} converges on one active code (AD-8).
 */
public record ReplaceInviteCode(
        HouseholdId householdId, InviteId newInviteId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public ReplaceInviteCode {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(newInviteId, "newInviteId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
