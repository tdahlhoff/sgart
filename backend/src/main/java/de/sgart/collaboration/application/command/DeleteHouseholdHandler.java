package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.InviteEmailSideStore;
import de.sgart.collaboration.application.exception.GovernanceNotPermittedApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.exception.GovernanceNotPermittedException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.application.RetractMembership;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.util.Objects;

/**
 * Orchestrates {@link DeleteHousehold} (AC2, AC7): resolve the caller, let {@link Household}
 * enforce the Admin-only gate (no last-Admin guard — deleting is allowed even for a sole Admin),
 * append, then synchronously de-link <strong>every</strong> ACL mapping for the household through
 * {@link RetractMembership#retractHousehold} — the "stop serving" that instantly 403s every scoped
 * query and drops the household from every member's switcher (locked decision 3/4). Read-model
 * purge is the projectors' job, not this handler's (decision 4).
 *
 * <p>{@code retractHousehold} de-links every mapping in one statement, so a caller who retries
 * after a failed retract still has a live mapping and can re-authorize — {@link
 * Household#deleteHousehold} checks the already-deleted no-op before the Admin gate, so the retry
 * still reaches (and re-runs) the retract even if the caller's role changed concurrently in the
 * meantime.
 *
 * <p>Also purges the raw email of every still-{@code PENDING} invite (AD-6): revoke and accept
 * already purge on their own invite, but a household delete otherwise never does, leaving pending
 * invites' raw emails behind — and undiscoverable once {@code invite_read_model} is purged.
 */
public final class DeleteHouseholdHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;
    private final RetractMembership retractMembership;
    private final InviteEmailSideStore inviteEmailSideStore;

    public DeleteHouseholdHandler(
            EventStore eventStore,
            ResolveMemberIdentity resolveMemberIdentity,
            RetractMembership retractMembership,
            InviteEmailSideStore inviteEmailSideStore) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.retractMembership = Objects.requireNonNull(retractMembership, "retractMembership must not be null");
        this.inviteEmailSideStore =
                Objects.requireNonNull(inviteEmailSideStore, "inviteEmailSideStore must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws NotAMemberException if the caller has no member mapping for the household (403)
     * @throws GovernanceNotPermittedApplicationException if the caller is not an Admin (403, AC2)
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);

        MemberId callerMemberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        DeleteHousehold command = new DeleteHousehold(householdId, commandId, loadedVersion);

        try {
            household.deleteHousehold(callerMemberId, command.commandId());
        } catch (GovernanceNotPermittedException notPermitted) {
            throw new GovernanceNotPermittedApplicationException(notPermitted.getMessage());
        }

        if (!household.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
        }

        retractMembership.retractHousehold(householdId);
        for (InviteId pendingInviteId : household.pendingInviteIds()) {
            inviteEmailSideStore.purge(pendingInviteId);
        }
    }
}
