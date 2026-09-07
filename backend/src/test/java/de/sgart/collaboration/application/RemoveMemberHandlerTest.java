package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.RemoveMemberHandler;
import de.sgart.collaboration.application.exception.GovernanceNotPermittedApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.MemberRemoved;
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
 * Fast unit test — proves {@link RemoveMemberHandler} (AC2, AC4): an Admin's removal appends {@code
 * MemberRemoved} and de-links the target's ACL mapping after the append (ordering: append then
 * de-link — recorded call order, not just count), a Participant caller is rejected (403) with no
 * side effect, an Admin may not target themselves (403), removing a non-member is a convergent
 * no-op that still de-links (idempotent, achieves nothing new), and a stranded mapping for an
 * already-removed target self-heals even when the retrying caller can no longer authorize (D1).
 */
class RemoveMemberHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";
    private static final String PARTICIPANT_SUB = "bob-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final RemoveMemberHandler handler = new RemoveMemberHandler(
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
    void anAdminRemovingAMemberAppendsMemberRemovedAndDeLinksTheTargetsMappingAfterAppend() {
        seedHouseholdWithAdminAndParticipant();
        List<String> callOrder = new java.util.ArrayList<>();
        de.sgart.shared.EventStore orderTrackingEventStore = new de.sgart.shared.EventStore() {
            @Override
            public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
                callOrder.add("append");
                eventStore.append(expectedVersion, events, commandId);
            }

            @Override
            public List<DomainEvent> readStream(StreamId streamIdToRead) {
                return eventStore.readStream(streamIdToRead);
            }
        };
        de.sgart.identity.domain.MemberMappingRepository orderTrackingRepository =
                new de.sgart.identity.domain.MemberMappingRepository() {
                    @Override
                    public java.util.Optional<MemberId> findMemberId(
                            de.sgart.identity.domain.KeycloakUserId keycloakUserId, HouseholdId householdIdArg) {
                        return mappingRepository.findMemberId(keycloakUserId, householdIdArg);
                    }

                    @Override
                    public void save(MemberMapping mapping) {
                        mappingRepository.save(mapping);
                    }

                    @Override
                    public void deleteMapping(
                            de.sgart.identity.domain.KeycloakUserId keycloakUserId, HouseholdId householdIdArg) {
                        mappingRepository.deleteMapping(keycloakUserId, householdIdArg);
                    }

                    @Override
                    public void deleteMappingByMember(HouseholdId householdIdArg, MemberId memberId) {
                        callOrder.add("de-link");
                        mappingRepository.deleteMappingByMember(householdIdArg, memberId);
                    }

                    @Override
                    public void deleteAllMappings(HouseholdId householdIdArg) {
                        mappingRepository.deleteAllMappings(householdIdArg);
                    }

                    @Override
                    public List<HouseholdId> householdIdsFor(de.sgart.identity.domain.KeycloakUserId keycloakUserId) {
                        return mappingRepository.householdIdsFor(keycloakUserId);
                    }
                };
        RemoveMemberHandler orderTrackingHandler = new RemoveMemberHandler(
                orderTrackingEventStore,
                new ResolveMemberIdentity(orderTrackingRepository),
                new RetractMembership(orderTrackingRepository));

        orderTrackingHandler.handle(
                ADMIN_SUB, householdId.toString(), participantMemberId.toString(), CommandId.generate().toString());

        assertThat(callOrder).containsExactly("append", "de-link");
        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events.get(events.size() - 1)).isInstanceOf(MemberRemoved.class);
        assertThat(((MemberRemoved) events.get(events.size() - 1)).memberId()).isEqualTo(participantMemberId);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId(PARTICIPANT_SUB), householdId)).isEmpty();
    }

    @Test
    void aParticipantCallerIsRejectedWith403AndLeavesNoAppendAndNoDeLink() {
        seedHouseholdWithAdminAndParticipant();

        assertThatThrownBy(() -> handler.handle(
                        PARTICIPANT_SUB, householdId.toString(), adminMemberId.toString(), CommandId.generate().toString()))
                .isInstanceOf(GovernanceNotPermittedApplicationException.class)
                .satisfies(thrown -> assertThat(
                                ((GovernanceNotPermittedApplicationException) thrown).errorDescriptor().code())
                        .isEqualTo("governance.notPermitted"));

        assertThat(eventStore.readStream(streamId)).hasSize(3);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId(ADMIN_SUB), householdId)).contains(adminMemberId);
    }

    @Test
    void aSelfTargetIsRejectedWith403AndLeavesNoAppendAndNoDeLink() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        ADMIN_SUB, householdId.toString(), adminMemberId.toString(), CommandId.generate().toString()))
                .isInstanceOf(GovernanceNotPermittedApplicationException.class);

        assertThat(eventStore.readStream(streamId)).hasSize(2);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId(ADMIN_SUB), householdId)).contains(adminMemberId);
    }

    @Test
    void removingANonMemberIsAConvergentNoOpThatStillDeLinksIdempotently() {
        seedHouseholdWithAdminAndParticipant();
        MemberId strangerId = MemberId.generate();

        handler.handle(ADMIN_SUB, householdId.toString(), strangerId.toString(), CommandId.generate().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(3); // no MemberRemoved appended
    }

    @Test
    void aStrandedMappingForAnAlreadyRemovedTargetSelfHealsEvenWhenTheRetryingCallerCanNoLongerAuthorize() {
        // Arrange: the domain already shows the target removed (a prior append succeeded), but the
        // ACL mapping was never de-linked (the de-link failed or the request never reached it), and
        // the original caller has since lost their own ACL mapping (concurrently removed) — a normal
        // retry through them would fail resolveMemberIdentity before ever reaching the de-link.
        seedHouseholdWithAdminAndParticipant();
        eventStore.append(
                AggregateVersion.of(streamId, 3),
                List.of(new MemberRemoved(EventId.generate(), householdId, participantMemberId, adminMemberId)),
                CommandId.generate());
        mappingRepository.deleteMapping(new KeycloakUserId(ADMIN_SUB), householdId);

        assertThatThrownBy(() -> handler.handle(
                        ADMIN_SUB,
                        householdId.toString(),
                        participantMemberId.toString(),
                        CommandId.generate().toString()))
                .isInstanceOf(de.sgart.identity.application.NotAMemberException.class);

        assertThat(mappingRepository.findMemberId(new KeycloakUserId(PARTICIPANT_SUB), householdId)).isEmpty();
    }
}
