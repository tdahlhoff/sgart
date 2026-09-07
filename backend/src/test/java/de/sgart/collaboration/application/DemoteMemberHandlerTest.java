package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.DemoteMemberHandler;
import de.sgart.collaboration.application.exception.GovernanceNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.LastAdminApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.MemberDemoted;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.ResolveMemberIdentity;
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
 * Fast unit test — proves {@link DemoteMemberHandler} (AC2, AC4, AC5): an Admin's demotion appends
 * {@code MemberDemoted} with <strong>no ACL de-link</strong> (a role change, not a removal), a
 * Participant caller is rejected (403), demoting the only Admin is rejected (409), and demoting an
 * already-Participant is a convergent no-op.
 */
class DemoteMemberHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";
    private static final String SECOND_ADMIN_SUB = "carla-sub";
    private static final String PARTICIPANT_SUB = "bob-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final DemoteMemberHandler handler =
            new DemoteMemberHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private void seedHousehold() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
    }

    @Test
    void anAdminDemotingAnotherAdminAppendsMemberDemotedAndDoesNotTouchTheAclMapping() {
        seedHousehold();
        MemberId secondAdminId = MemberId.generate();
        eventStore.append(
                AggregateVersion.of(streamId, 2),
                List.of(new MemberJoined(EventId.generate(), householdId, secondAdminId, HouseholdRole.ADMIN)),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, secondAdminId, new KeycloakUserId(SECOND_ADMIN_SUB)));

        handler.handle(ADMIN_SUB, householdId.toString(), secondAdminId.toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events.get(events.size() - 1)).isInstanceOf(MemberDemoted.class);
        assertThat(((MemberDemoted) events.get(events.size() - 1)).memberId()).isEqualTo(secondAdminId);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId(SECOND_ADMIN_SUB), householdId))
                .contains(secondAdminId);
    }

    @Test
    void aParticipantCallerIsRejectedWith403() {
        seedHousehold();
        MemberId participantId = MemberId.generate();
        eventStore.append(
                AggregateVersion.of(streamId, 2),
                List.of(new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT)),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, participantId, new KeycloakUserId(PARTICIPANT_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        PARTICIPANT_SUB, householdId.toString(), adminMemberId.toString(), CommandId.generate().toString()))
                .isInstanceOf(GovernanceNotPermittedApplicationException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(3);
    }

    @Test
    void demotingTheOnlyAdminIsRejectedWith409() {
        seedHousehold();

        assertThatThrownBy(() -> handler.handle(
                        ADMIN_SUB, householdId.toString(), adminMemberId.toString(), CommandId.generate().toString()))
                .isInstanceOf(LastAdminApplicationException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void demotingAnAlreadyParticipantIsAConvergentNoOp() {
        seedHousehold();
        MemberId participantId = MemberId.generate();
        eventStore.append(
                AggregateVersion.of(streamId, 2),
                List.of(new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT)),
                CommandId.generate());

        handler.handle(ADMIN_SUB, householdId.toString(), participantId.toString(), CommandId.generate().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(3); // no MemberDemoted appended
    }
}
