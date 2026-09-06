package de.sgart.collaboration.application.command;

import de.sgart.collaboration.domain.EmailHmac;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import java.util.Objects;

/**
 * The caller's intention to invite a person by email (Story 4.1, AC1). Mirrors {@link AddStore}:
 * {@code basedOnVersion} is the loaded household-stream version (online load-then-append, AD-8);
 * the {@link InviteId} is minted client-side so the {@code POST} response needs no body
 * (read-your-writes). Carries {@link EmailHmac}, never the raw email (AD-6) — the handler computes
 * the digest before constructing this command.
 */
public record InvitePerson(
        HouseholdId householdId,
        InviteId inviteId,
        EmailHmac emailHmac,
        CommandId commandId,
        AggregateVersion basedOnVersion)
        implements Command {

    public InvitePerson {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(emailHmac, "emailHmac must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
    }
}
