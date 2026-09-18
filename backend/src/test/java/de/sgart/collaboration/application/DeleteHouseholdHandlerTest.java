package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.DeleteHouseholdHandler;
import de.sgart.collaboration.application.exception.GovernanceNotPermittedApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.HouseholdDeleted;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.application.RetractMembership;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — proves {@link DeleteHouseholdHandler} (AC2, AC7): an Admin's deletion appends
 * {@code HouseholdDeleted} and de-links <strong>every</strong> ACL mapping for the household after
 * the append, a Participant caller is rejected (403) with no side effect, and deleting an
 * already-deleted household is a convergent no-op.
 */
class DeleteHouseholdHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";
    private static final String PARTICIPANT_SUB = "bob-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final DeleteHouseholdHandler handler = new DeleteHouseholdHandler(
            eventStore, new ResolveMemberIdentity(mappingRepository), new RetractMembership(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final MemberId participantMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private void seedHouseholdWithAdminAndParticipant() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        eventStore.append(
                AggregateVersion.of(streamId, 2),
                List.of(new MemberJoined(EventId.generate(), householdId, participantMemberId, HouseholdRole.PARTICIPANT)),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
        mappingRepository.save(new MemberMapping(householdId, participantMemberId, new KeycloakUserId(PARTICIPANT_SUB)));
    }

    @Test
    void anAdminDeletingTheHouseholdAppendsHouseholdDeletedAndDeLinksEveryMapping() {
        seedHouseholdWithAdminAndParticipant();

        handler.handle(ADMIN_SUB, householdId.toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events.get(events.size() - 1)).isInstanceOf(HouseholdDeleted.class);
        assertThat(((HouseholdDeleted) events.get(events.size() - 1)).deletedBy()).isEqualTo(adminMemberId);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId(ADMIN_SUB), householdId)).isEmpty();
        assertThat(mappingRepository.findMemberId(new KeycloakUserId(PARTICIPANT_SUB), householdId)).isEmpty();
    }

    @Test
    void aParticipantCallerIsRejectedWith403AndLeavesNoAppendAndNoDeLink() {
        seedHouseholdWithAdminAndParticipant();

        assertThatThrownBy(() ->
                        handler.handle(PARTICIPANT_SUB, householdId.toString(), CommandId.generate().toString()))
                .isInstanceOf(GovernanceNotPermittedApplicationException.class);

        assertThat(eventStore.readStream(streamId)).hasSize(3);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId(ADMIN_SUB), householdId)).contains(adminMemberId);
    }

    @Test
    void deletingAnAlreadyDeletedHouseholdIsAConvergentNoOp() {
        seedHouseholdWithAdminAndParticipant();
        handler.handle(ADMIN_SUB, householdId.toString(), CommandId.generate().toString());
        // Admin's own mapping is now de-linked; resolve directly via the aggregate call for the retry.
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));

        household.deleteHousehold(adminMemberId, CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }
}
