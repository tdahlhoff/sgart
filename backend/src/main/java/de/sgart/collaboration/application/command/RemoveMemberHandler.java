package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
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
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.util.Objects;

/**
 * Orchestrates {@link RemoveMember} (AC2, AC4): resolve the caller, let {@link Household} enforce
 * the Admin-only gate (self-target is rejected by the domain — a voluntary departure goes through
 * {@link LeaveHouseholdHandler}), append, then synchronously de-link the <strong>target's</strong>
 * ACL mapping (locked decision 3). A rejected governance call throws before append — no mapping is
 * ever touched for a rejected caller (the 4.2 F1 discipline).
 *
 * <p><strong>Caller-independent self-heal:</strong> the household is rehydrated before the caller is
 * resolved/authorized. If it already shows the target as removed — a prior attempt appended but its
 * de-link failed or never ran — the mapping is retracted immediately, before any authorization check.
 * This way a concurrent demote/removal of the retrying caller (who may no longer be able to
 * authorize a fresh removal) cannot strand a live ACL mapping for the target.
 */
public final class RemoveMemberHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;
    private final RetractMembership retractMembership;

    public RemoveMemberHandler(
            EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity, RetractMembership retractMembership) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.retractMembership = Objects.requireNonNull(retractMembership, "retractMembership must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws NotAMemberException if the caller has no member mapping for the household (403)
     * @throws GovernanceNotPermittedApplicationException if the caller is not an Admin, or targets
     *     themselves (403, AC2)
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawTargetMemberId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        MemberId targetMemberId = CommandFieldTranslations.toMemberId(rawTargetMemberId);

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));

        if (!household.isMember(targetMemberId)) {
            retractMembership.retractMember(householdId, targetMemberId);
        }

        MemberId callerMemberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        AggregateVersion loadedVersion = household.version();
        RemoveMember command = new RemoveMember(householdId, targetMemberId, commandId, loadedVersion);

        try {
            household.removeMember(callerMemberId, command.targetMemberId(), command.commandId());
        } catch (GovernanceNotPermittedException notPermitted) {
            throw new GovernanceNotPermittedApplicationException(notPermitted.getMessage());
        }

        if (!household.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
        }

        retractMembership.retractMember(householdId, targetMemberId);
    }
}
