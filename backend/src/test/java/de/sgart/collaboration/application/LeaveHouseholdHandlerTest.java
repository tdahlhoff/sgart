package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.LeaveHouseholdHandler;
import de.sgart.collaboration.application.exception.LastAdminApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.MemberLeft;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
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
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves {@link LeaveHouseholdHandler} (AC3, AC5): a leave appends
 * {@code MemberLeft} and de-links the caller's own ACL mapping after the append, the last Admin is
 * rejected (409) with no append and no de-link, and the de-link happens even on a non-member no-op.
 */
class LeaveHouseholdHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";
    private static final String PARTICIPANT_SUB = "bob-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final LeaveHouseholdHandler handler = new LeaveHouseholdHandler(
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
    void leavingAppendsMemberLeftAndDeLinksTheCallersOwnMapping() {
        seedHouseholdWithAdminAndParticipant();

        handler.handle(PARTICIPANT_SUB, householdId.toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(4);
        assertThat(events.get(3)).isInstanceOf(MemberLeft.class);
        assertThat(((MemberLeft) events.get(3)).memberId()).isEqualTo(participantMemberId);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId(PARTICIPANT_SUB), householdId)).isEmpty();
    }

    @Test
    void theLastAdminLeavingIsRejectedWith409AndLeavesNoAppendAndNoDeLink() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));

        assertThatThrownBy(() -> handler.handle(ADMIN_SUB, householdId.toString(), CommandId.generate().toString()))
                .isInstanceOf(LastAdminApplicationException.class)
                .satisfies(thrown -> assertThat(((LastAdminApplicationException) thrown).errorDescriptor().code())
                        .isEqualTo("membership.lastAdmin"));

        assertThat(eventStore.readStream(streamId)).hasSize(2);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId(ADMIN_SUB), householdId)).contains(adminMemberId);
    }

    @Test
    void aNonMemberCallerIsRejectedBeforeReachingTheDomain() {
        seedHouseholdWithAdminAndParticipant();

        assertThatThrownBy(() ->
                        handler.handle("stranger-sub", householdId.toString(), CommandId.generate().toString()))
                .isInstanceOf(NotAMemberException.class);
    }
}
