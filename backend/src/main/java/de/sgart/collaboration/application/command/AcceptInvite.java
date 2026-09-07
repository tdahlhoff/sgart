package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import java.util.Objects;

/**
 * The caller's intention to redeem a personal invite and join the household (Story 4.2, AC1).
 * Mirrors {@link InvitePerson}: {@code basedOnVersion} is the loaded household-stream version
 * (online load-then-append, AD-8). Carries no email or role — the invite is a bearer capability
 * (locked decision 3) and the role is fixed {@code PARTICIPANT} in the domain; the joiner id comes
 * from the JWT via {@code MintMemberIdentity}, never the request body.
 */
public record AcceptInvite(HouseholdId householdId, InviteId inviteId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public AcceptInvite {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
