package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.InviteEmailSideStore;
import de.sgart.collaboration.application.exception.GovernanceNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.InviteNotFoundApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.exception.GovernanceNotPermittedException;
import de.sgart.collaboration.domain.exception.InviteNotFoundException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.util.Objects;

/**
 * Orchestrates {@link RevokeInvite} (AC2, AC6): resolve the caller, let {@link Household} enforce
 * the Admin-only gate and the invite state machine, append, then purge the invite's raw-email
 * side-store row (AD-6) — mirrors {@link de.sgart.collaboration.application.command.AcceptInviteHandler}'s
 * purge-after-append exactly. A no-op revoke (already {@code REVOKED}) still purges idempotently, so
 * even a repeated revoke converges on "no raw email left".
 */
public final class RevokeInviteHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;
    private final InviteEmailSideStore inviteEmailSideStore;

    public RevokeInviteHandler(
            EventStore eventStore,
            ResolveMemberIdentity resolveMemberIdentity,
            InviteEmailSideStore inviteEmailSideStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.inviteEmailSideStore =
                Objects.requireNonNull(inviteEmailSideStore, "inviteEmailSideStore must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws NotAMemberException if the caller has no member mapping for the household (403)
     * @throws GovernanceNotPermittedApplicationException if the caller is not an Admin (403, AC2)
     * @throws InviteNotFoundApplicationException if there is no pending invite to revoke (404, AC6)
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawInviteId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        InviteId inviteId = CommandFieldTranslations.toInviteId(rawInviteId);

        MemberId callerMemberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        RevokeInvite command = new RevokeInvite(householdId, inviteId, commandId, loadedVersion);

        try {
            household.revokeInvite(callerMemberId, command.inviteId(), command.commandId());
        } catch (GovernanceNotPermittedException notPermitted) {
            throw new GovernanceNotPermittedApplicationException(notPermitted.getMessage());
        } catch (InviteNotFoundException notFound) {
            throw new InviteNotFoundApplicationException(notFound.getMessage());
        }

        if (!household.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
        }

        // After a successful call only (no exception thrown above): purge, idempotent even on the
        // already-REVOKED no-op branch (AD-6).
        inviteEmailSideStore.purge(inviteId);
    }
}
