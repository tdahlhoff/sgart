package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.RenameNotPermittedException;
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
 * Orchestrates {@link RenameHousehold} (AC3, AC4): resolve the caller's household-scoped
 * {@link MemberId} through the Identity ACL's published {@link ResolveMemberIdentity} port (AD-2 —
 * never {@code identity.domain}), load the aggregate, and let it enforce the Admin-only rename
 * invariant (AC4). The append uses the <em>loaded</em> stream version as the expected version
 * (online load-then-append, Clarification D); a concurrent rename loses with the store's
 * {@code ConcurrencyConflictException} (→ 409, AD-8). A no-change rename raises nothing, so the
 * append is skipped entirely (convergent no-op, AD-8). The command returns {@code void} — a command
 * yields no domain data (CQRS); the client already knows the id and the name it sent.
 */
public final class RenameHouseholdHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public RenameHouseholdHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub} by
     *     {@code adapter.in} — never accepted from the request body (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if {@code rawCommandId} is missing or not a UUID (400)
     * @throws InvalidHouseholdNameException if {@code rawName} fails the domain invariant (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws RenameNotPermittedApplicationException if the caller is a member but not an Admin (403)
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawName, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdName newName = CommandFieldTranslations.toHouseholdName(rawName);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);

        // A non-member never reaches rename — NotAMemberException propagates as a 403 (AD-2/AD-5).
        MemberId memberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        RenameHousehold command = new RenameHousehold(householdId, newName, commandId, loadedVersion);

        try {
            household.rename(memberId, command.newName(), command.commandId());
        } catch (RenameNotPermittedException notAnAdmin) {
            throw new RenameNotPermittedApplicationException(notAnAdmin.getMessage());
        }

        if (!household.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
        }
    }
}
