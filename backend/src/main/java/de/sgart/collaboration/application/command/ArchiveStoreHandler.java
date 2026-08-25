package de.sgart.collaboration.application.command;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.NotAHouseholdMemberApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.exception.NotAHouseholdMemberException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import java.util.Objects;

/**
 * Orchestrates {@link ArchiveStore} (AC3): resolve the caller's {@link MemberId} through the
 * Identity ACL (AD-2), load the {@link Household}, and let it archive the store. Archiving an
 * already-archived or unknown store raises nothing, so the append is skipped entirely (convergent
 * no-op, AD-8), mirroring {@link RenameHouseholdHandler}'s no-op handling. Returns {@code void}
 * (command → no domain data, CQRS).
 */
public final class ArchiveStoreHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public ArchiveStoreHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws NotAMemberException if the caller has no member mapping for the household (403)
     * @throws NotAHouseholdMemberApplicationException if the caller resolves to a member the
     *     household's stream never recorded (403, ACL/event-stream divergence)
     */
    public void handle(String keycloakUserId, String rawHouseholdId, String rawStoreId, String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        StoreId storeId = CommandFieldTranslations.toStoreId(rawStoreId);

        MemberId memberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        ArchiveStore command = new ArchiveStore(householdId, storeId, commandId, loadedVersion);

        try {
            household.archiveStore(memberId, command.storeId(), command.commandId());
        } catch (NotAHouseholdMemberException notAMember) {
            throw new NotAHouseholdMemberApplicationException(notAMember.getMessage());
        }

        if (!household.uncommittedEvents().isEmpty()) {
            eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
        }
    }
}
