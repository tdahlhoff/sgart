package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.PromoteMemberHandler;
import de.sgart.collaboration.application.exception.GovernanceNotPermittedApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.MemberPromoted;
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
 * Fast unit test — proves {@link PromoteMemberHandler} (AC2, AC4): an Admin's promotion appends
 * {@code MemberPromoted} with <strong>no ACL de-link</strong> (a role change, not a removal), a
 * Participant caller is rejected (403), and promoting an already-Admin is a convergent no-op.
 */
class PromoteMemberHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";
    private static final String PARTICIPANT_SUB = "bob-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final PromoteMemberHandler handler =
            new PromoteMemberHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

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
    void anAdminPromotingAParticipantAppendsMemberPromotedAndDoesNotTouchTheAclMapping() {
        seedHouseholdWithAdminAndParticipant();

        handler.handle(
                ADMIN_SUB, householdId.toString(), participantMemberId.toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events.get(events.size() - 1)).isInstanceOf(MemberPromoted.class);
        assertThat(((MemberPromoted) events.get(events.size() - 1)).memberId()).isEqualTo(participantMemberId);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId(PARTICIPANT_SUB), householdId))
                .contains(participantMemberId);
    }

    @Test
    void aParticipantCallerIsRejectedWith403() {
        seedHouseholdWithAdminAndParticipant();

        assertThatThrownBy(() -> handler.handle(
                        PARTICIPANT_SUB, householdId.toString(), adminMemberId.toString(), CommandId.generate().toString()))
                .isInstanceOf(GovernanceNotPermittedApplicationException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(3);
    }

    @Test
    void promotingAnAlreadyAdminIsAConvergentNoOp() {
        seedHouseholdWithAdminAndParticipant();
        MemberId secondAdminId = MemberId.generate();
        eventStore.append(
                AggregateVersion.of(streamId, 3),
                List.of(new MemberJoined(EventId.generate(), householdId, secondAdminId, HouseholdRole.ADMIN)),
                CommandId.generate());

        handler.handle(ADMIN_SUB, householdId.toString(), secondAdminId.toString(), CommandId.generate().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(4); // no MemberPromoted appended
    }
}
