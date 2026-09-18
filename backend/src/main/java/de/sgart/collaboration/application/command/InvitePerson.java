package de.sgart.collaboration.application.command;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import java.util.Objects;

/**
 * The caller's intention to invite a person by join code/link (Story 7.5, AC1). Mirrors {@link
 * AddStore}: {@code basedOnVersion} is the loaded household-stream version (online load-then-
 * append, AD-8); the {@link InviteId} is generated client-side so the {@code POST} response needs
 * no body (read-your-writes). Carries no email or email-derived field (AD-6).
 */
public record InvitePerson(
        HouseholdId householdId, InviteId inviteId, CommandId commandId, AggregateVersion basedOnVersion)
        implements Command {

    public InvitePerson {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
