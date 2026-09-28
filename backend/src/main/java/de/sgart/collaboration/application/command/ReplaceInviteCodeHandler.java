package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.GovernanceNotPermittedApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.exception.GovernanceNotPermittedException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.time.Clock;
import java.util.Objects;

/**
 * Orchestrates {@link ReplaceInviteCode} (Story 8.4, F7): resolve the caller, let {@link Household}
 * enforce the Admin-only gate and invalidate-then-issue atomically, then append. Any Admin may
 * replace the code — not creator-only (the last-Admin invariant guarantees someone always can).
 */
public final class ReplaceInviteCodeHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;
    private final Clock clock;

    public ReplaceInviteCodeHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity, Clock clock) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws NotAMemberException if the caller has no member mapping for the household (403)
     * @throws GovernanceNotPermittedApplicationException if the caller is not an Admin (403)
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawNewInviteId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        InviteId newInviteId = CommandFieldTranslations.toInviteId(rawNewInviteId);

        MemberId callerMemberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        ReplaceInviteCode command = new ReplaceInviteCode(householdId, newInviteId, commandId, loadedVersion);

        try {
            household.replaceInviteCode(callerMemberId, command.newInviteId(), clock.instant(), command.commandId());
        } catch (GovernanceNotPermittedException notPermitted) {
            throw new GovernanceNotPermittedApplicationException(notPermitted.getMessage());
        }

        if (!household.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
        }
    }
}
