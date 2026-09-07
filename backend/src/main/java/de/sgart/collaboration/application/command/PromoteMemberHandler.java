package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.GovernanceNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.NotAHouseholdMemberApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.exception.GovernanceNotPermittedException;
import de.sgart.collaboration.domain.exception.NotAHouseholdMemberException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import java.util.Objects;

/**
 * Orchestrates {@link PromoteMember} (AC2, AC4): resolve the caller, let {@link Household} enforce
 * the Admin-only gate, append. A role change, not a membership removal — <strong>no ACL de-link</strong>
 * (unlike {@link RemoveMemberHandler}/{@link LeaveHouseholdHandler}).
 */
public final class PromoteMemberHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public PromoteMemberHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws NotAMemberException if the caller has no member mapping for the household (403)
     * @throws GovernanceNotPermittedApplicationException if the caller is not an Admin (403, AC2)
     * @throws NotAHouseholdMemberApplicationException if the target is not a member (403)
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawTargetMemberId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        MemberId targetMemberId = CommandFieldTranslations.toMemberId(rawTargetMemberId);

        MemberId callerMemberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        PromoteMember command = new PromoteMember(householdId, targetMemberId, commandId, loadedVersion);

        try {
            household.promoteMember(callerMemberId, command.targetMemberId(), command.commandId());
        } catch (GovernanceNotPermittedException notPermitted) {
            throw new GovernanceNotPermittedApplicationException(notPermitted.getMessage());
        } catch (NotAHouseholdMemberException notAMember) {
            throw new NotAHouseholdMemberApplicationException(notAMember.getMessage());
        }

        if (!household.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
        }
    }
}
