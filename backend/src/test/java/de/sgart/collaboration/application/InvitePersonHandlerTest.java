package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.InvitePersonHandler;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.NotAHouseholdMemberApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.MemberInvited;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the invite command path (Story 7.5, AC1): a member's invite
 * appends {@code MemberInvited} with no email collected anywhere, invite is not Admin-gated (a
 * Participant succeeds too, Story 4.3 T28), a non-member is rejected (403), and inviting the same
 * household twice creates two independent pending invites (no duplicate-by-email rejection).
 */
class InvitePersonHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";
    private static final String PARTICIPANT_SUB = "bob-sub";
    private static final Instant FIXED_NOW = Instant.parse("2026-09-06T10:00:00Z");

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final RecordingInviteLinkFactory inviteLinkFactory =
            new RecordingInviteLinkFactory("http://localhost:8081/invite");
    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private InvitePersonHandler handler() {
        return handler(fixedClock, false);
    }

    private InvitePersonHandler handler(Clock clock, boolean logInviteLinkForDevTesting) {
        return new InvitePersonHandler(
                eventStore, new ResolveMemberIdentity(mappingRepository), inviteLinkFactory, clock, logInviteLinkForDevTesting);
    }

    private void seedHouseholdWithAdmin() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
    }

    private MemberId seedHouseholdWithAdminAndParticipant() {
        seedHouseholdWithAdmin();
        MemberId participantId = MemberId.generate();
        eventStore.append(
                AggregateVersion.of(streamId, 2),
                List.of(new de.sgart.collaboration.domain.event.MemberJoined(
                        de.sgart.shared.EventId.generate(),
                        householdId,
                        participantId,
                        de.sgart.collaboration.domain.HouseholdRole.PARTICIPANT)),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, participantId, new KeycloakUserId(PARTICIPANT_SUB)));
        return participantId;
    }

    @Test
    void invitingAPersonAppendsMemberInvitedWithNoEmail() {
        seedHouseholdWithAdmin();

        handler().handle(ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOf(MemberInvited.class);
        MemberInvited invited = (MemberInvited) events.get(2);
        assertThat(invited.invitedBy()).isEqualTo(adminMemberId);
    }

    @Test
    void aParticipantMemberCanSendAnInvite() {
        seedHouseholdWithAdminAndParticipant();

        handler().handle(
                PARTICIPANT_SUB, householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events.get(events.size() - 1)).isInstanceOf(MemberInvited.class);
    }

    @Test
    void rejectsAnInviteFromANonMemberWith403() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() -> handler().handle(
                        "stranger-sub", householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString()))
                .isInstanceOf(NotAMemberException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void translatesTheAggregateMembershipGuardIntoAnApplicationException() {
        seedHouseholdWithAdmin();
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId("ghost-sub")));

        assertThatThrownBy(() -> handler().handle(
                        "ghost-sub", householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString()))
                .isInstanceOf(NotAHouseholdMemberApplicationException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void invitePerson_sameHouseholdTwice_createsTwoIndependentInvites() {
        seedHouseholdWithAdmin();
        handler().handle(ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString());

        handler().handle(ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(4); // HouseholdCreated + MemberJoined + two independent MemberInvited
        assertThat(events.get(2)).isInstanceOf(MemberInvited.class);
        assertThat(events.get(3)).isInstanceOf(MemberInvited.class);
        assertThat(((MemberInvited) events.get(2)).inviteId())
                .isNotEqualTo(((MemberInvited) events.get(3)).inviteId());
    }

    @Test
    void invitingAPersonUnderDevLinkLoggingBuildsTheInviteLinkFromTheOpaqueIdsOnly() {
        seedHouseholdWithAdmin();
        InviteId inviteId = InviteId.generate();

        handler(fixedClock, true).handle(ADMIN_SUB, householdId.toString(), inviteId.toString(), CommandId.generate().toString());

        assertThat(inviteLinkFactory.builtLinks).hasSize(1);
        assertThat(inviteLinkFactory.builtLinks.get(0))
                .isEqualTo("http://localhost:8081/invite?h=" + householdId + "&i=" + inviteId);
    }

    @Test
    void invitingAPersonWithoutDevLinkLoggingDoesNotBuildTheInviteLink() {
        seedHouseholdWithAdmin();

        handler().handle(ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString());

        assertThat(inviteLinkFactory.builtLinks).isEmpty();
    }

    @Test
    void mapsAMalformedInviteIdToInviteIdInvalid() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() ->
                        handler().handle(ADMIN_SUB, householdId.toString(), "not-a-uuid", CommandId.generate().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.inviteIdInvalid"));
    }

    /** Records every link built, so a test can assert the factory is invoked only when dev
     * link-logging is enabled without pulling in a mocking framework. */
    private static final class RecordingInviteLinkFactory extends InviteLinkFactory {
        private final List<String> builtLinks = new ArrayList<>();

        RecordingInviteLinkFactory(String baseUrl) {
            super(baseUrl);
        }

        @Override
        public String buildLink(HouseholdId householdId, InviteId inviteId) {
            String link = super.buildLink(householdId, inviteId);
            builtLinks.add(link);
            return link;
        }
    }
}
