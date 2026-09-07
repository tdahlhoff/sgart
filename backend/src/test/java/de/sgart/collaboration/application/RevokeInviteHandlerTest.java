package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.RevokeInviteHandler;
import de.sgart.collaboration.application.exception.GovernanceNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.InviteNotFoundApplicationException;
import de.sgart.collaboration.domain.EmailHmac;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.InviteRevoked;
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
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — proves {@link RevokeInviteHandler} (AC2, AC6): an Admin's revoke appends {@code
 * InviteRevoked} and purges the side-store row after the append (mirrors {@code
 * AcceptInviteHandlerTest}'s purge-after-append discipline), a Participant caller is rejected (403)
 * with no side effect, an absent invite is rejected (404), and revoking an already-revoked invite
 * is a convergent no-op that still purges idempotently.
 */
class RevokeInviteHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";
    private static final String PARTICIPANT_SUB = "bob-sub";
    private static final Instant INVITED_AT = Instant.parse("2026-09-06T10:00:00Z");

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final FakeInviteEmailSideStore sideStore = new FakeInviteEmailSideStore();
    private final RevokeInviteHandler handler =
            new RevokeInviteHandler(eventStore, new ResolveMemberIdentity(mappingRepository), sideStore);

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private InviteId seedHouseholdWithAPendingInvite() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        InviteId inviteId = InviteId.generate();
        household.invitePerson(adminMemberId, inviteId, new EmailHmac("hmac-1"), INVITED_AT, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
        sideStore.store(inviteId, NormalizedEmail.fromRaw("anna@example.com"));
        return inviteId;
    }

    @Test
    void anAdminRevokingAPendingInviteAppendsInviteRevokedAndPurgesTheSideStoreAfterAppend() {
        InviteId inviteId = seedHouseholdWithAPendingInvite();

        handler.handle(ADMIN_SUB, householdId.toString(), inviteId.toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events.get(events.size() - 1)).isInstanceOf(InviteRevoked.class);
        assertThat(((InviteRevoked) events.get(events.size() - 1)).inviteId()).isEqualTo(inviteId);
        assertThat(sideStore.findEmail(inviteId)).isEmpty();
        assertThat(sideStore.appendWasVisibleOnPurgeCall).isTrue();
    }

    @Test
    void aParticipantCallerIsRejectedWith403AndLeavesNoSideEffect() {
        InviteId inviteId = seedHouseholdWithAPendingInvite();
        MemberId participantId = MemberId.generate();
        eventStore.append(
                AggregateVersion.of(streamId, 3),
                List.of(new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT)),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, participantId, new KeycloakUserId(PARTICIPANT_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        PARTICIPANT_SUB, householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(GovernanceNotPermittedApplicationException.class);

        assertThat(eventStore.readStream(streamId)).hasSize(4);
        assertThat(sideStore.findEmail(inviteId)).isPresent();
    }

    @Test
    void anAbsentInviteIsRejectedWith404() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));

        assertThatThrownBy(() -> handler.handle(
                        ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteNotFoundApplicationException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void revokingAnAlreadyRevokedInviteIsAConvergentNoOpThatStillPurgesIdempotently() {
        InviteId inviteId = seedHouseholdWithAPendingInvite();
        handler.handle(ADMIN_SUB, householdId.toString(), inviteId.toString(), CommandId.generate().toString());
        int eventsAfterFirstRevoke = eventStore.readStream(streamId).size();

        handler.handle(ADMIN_SUB, householdId.toString(), inviteId.toString(), CommandId.generate().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(eventsAfterFirstRevoke); // no second InviteRevoked
        assertThat(sideStore.purgeCallCount).isEqualTo(2);
    }

    /** Mirrors {@code AcceptInviteHandlerTest}'s fake — every {@code purge()} call re-checks the
     * stream for the consuming {@code InviteRevoked}, proving append-before-purge ordering. */
    private final class FakeInviteEmailSideStore implements InviteEmailSideStore {
        private final Map<InviteId, NormalizedEmail> emailsByInviteId = new HashMap<>();
        private int purgeCallCount = 0;
        private boolean appendWasVisibleOnPurgeCall = true;

        @Override
        public void store(InviteId inviteId, NormalizedEmail email) {
            emailsByInviteId.put(inviteId, email);
        }

        @Override
        public void purge(InviteId inviteId) {
            purgeCallCount++;
            boolean consumingEventVisible = eventStore.readStream(streamId).stream()
                    .anyMatch(event -> event instanceof InviteRevoked revoked && revoked.inviteId().equals(inviteId));
            appendWasVisibleOnPurgeCall = appendWasVisibleOnPurgeCall && consumingEventVisible;
            emailsByInviteId.remove(inviteId);
        }

        @Override
        public Optional<NormalizedEmail> findEmail(InviteId inviteId) {
            return Optional.ofNullable(emailsByInviteId.get(inviteId));
        }
    }
}
