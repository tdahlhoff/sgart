package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.LastAdminApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.exception.LastAdminException;
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
 * Orchestrates {@link LeaveHousehold} (AC3, AC5): resolve the caller's {@link MemberId} through the
 * Identity ACL, let {@link Household} enforce the last-Admin invariant (AC5), append, then
 * synchronously de-link the caller's own ACL mapping through {@link RetractMembership} — the crux
 * fix for Story 4.2's F1 ghost-member leak (locked decision 3, the story's "read this twice"
 * section). <strong>Append-before-de-link ordering</strong>: a lost append must not have already
 * revoked access; an append-ok/de-link-fail leaves the member on-stream but still mapped (access
 * retained, benign, self-corrects on retry) — never the reverse. The de-link runs even on a
 * convergent no-op (not currently a member): idempotent, achieves nothing new, but keeps the
 * end-state correct under an ACL/event-stream divergence. The client re-bootstraps to re-route
 * (AC9) — this command returns {@code void} (CQRS).
 */
public final class LeaveHouseholdHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;
    private final RetractMembership retractMembership;

    public LeaveHouseholdHandler(
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
     * @throws LastAdminApplicationException if the caller is the household's last Admin (409, AC5)
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);

        MemberId callerMemberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        LeaveHousehold command = new LeaveHousehold(householdId, commandId, loadedVersion);

        try {
            household.leaveHousehold(callerMemberId, command.commandId());
        } catch (LastAdminException lastAdmin) {
            throw new LastAdminApplicationException(lastAdmin.getMessage());
        }

        if (!household.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
        }

        // After a successful call only (no exception thrown above): de-link, idempotent even on a
        // no-op. Never reached on a rejected LastAdmin call.
        retractMembership.retractMember(householdId, callerMemberId);
    }
}
