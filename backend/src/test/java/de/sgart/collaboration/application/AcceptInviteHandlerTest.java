package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AcceptInviteHandler;
import de.sgart.collaboration.application.exception.ConsentRequiredException;
import de.sgart.collaboration.application.exception.InviteAlreadyConsumedApplicationException;
import de.sgart.collaboration.application.exception.InviteExpiredApplicationException;
import de.sgart.collaboration.application.exception.InviteNotFoundApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.InviteAccepted;
import de.sgart.collaboration.domain.event.InviteExpired;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.IssueMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the accept command path (AC1–AC5): a valid invite appends
 * {@code InviteAccepted} + {@code MemberJoined}, an already-member joiner appends only {@code
 * InviteAccepted}, a past-TTL invite is expired-then-rejected (410) with the lazy transition
 * persisted, an already-expired invite is rejected with nothing appended, an unknown invite is
 * rejected (404) with nothing appended, and a same-{@code commandId} retry converges via the
 * event store's own dedup.
 */
class AcceptInviteHandlerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-06T10:00:00Z");

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(mappingRepository);

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private AcceptInviteHandler handler(Clock clock) {
        return new AcceptInviteHandler(eventStore, issueMemberIdentity, clock, alwaysConsentingGate());
    }

    private static ConsentGate alwaysConsentingGate() {
        return keycloakUserId -> true;
    }

    private AcceptInviteHandler handler() {
        return handler(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private InviteId seedHouseholdWithAPendingInvite(Instant invitedAt) {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        InviteId inviteId = InviteId.generate();
        household.invitePerson(adminMemberId, inviteId, invitedAt, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        return inviteId;
    }

    @Test
    void acceptInvite_withoutRecordedConsent_isRejectedWith409ConsentRequired() {
        InviteId inviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        AcceptInviteHandler handler =
                new AcceptInviteHandler(eventStore, issueMemberIdentity, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), never -> false);

        assertThatThrownBy(() -> handler.handle(
                        "anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(ConsentRequiredException.class);

        assertThat(eventStore.readStream(streamId)).hasSize(3);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId("anna-sub"), householdId)).isEmpty();
    }

    @Test
    void acceptingAValidInviteAppendsInviteAcceptedAndMemberJoined() {
        InviteId inviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);

        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(5);
        assertThat(events.get(3)).isInstanceOf(InviteAccepted.class);
        assertThat(((InviteAccepted) events.get(3)).inviteId()).isEqualTo(inviteId);
        assertThat(events.get(4)).isInstanceOf(MemberJoined.class);
    }

    @Test
    void acceptInvite_whenCallerAlreadyMember_isANoOp_noDuplicateMembership() {
        InviteId firstInviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        handler().handle("anna-sub", householdId.toString(), firstInviteId.toString(), CommandId.generate().toString());

        // Anna accepts a second, independent personal invite to the same household (E5): the issue
        // replays her existing MemberId, so this must be a joined-outcome with no second MemberJoined.
        Household forSecondInvite = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion versionBeforeSecondInvite = forSecondInvite.version();
        InviteId secondInviteId = InviteId.generate();
        forSecondInvite.invitePerson(adminMemberId, secondInviteId, FIXED_NOW, CommandId.generate());
        eventStore.append(versionBeforeSecondInvite, forSecondInvite.uncommittedEvents(), CommandId.generate());

        handler().handle("anna-sub", householdId.toString(), secondInviteId.toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        DomainEvent lastEvent = events.get(events.size() - 1);
        assertThat(lastEvent).isInstanceOf(InviteAccepted.class);
        assertThat(((InviteAccepted) lastEvent).inviteId()).isEqualTo(secondInviteId);
        assertThat(events.stream().filter(MemberJoined.class::isInstance)).hasSize(2); // admin + anna, once each
    }

    @Test
    void aPastTtlInviteIsExpiredAndAppendedThenRejectedWith410() {
        InviteId inviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        Clock muchLaterClock = Clock.fixed(FIXED_NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC);

        assertThatThrownBy(() -> handler(muchLaterClock)
                        .handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteExpiredApplicationException.class);

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(4);
        assertThat(events.get(3)).isInstanceOf(InviteExpired.class);
    }

    @Test
    void anAlreadyExpiredInviteIsRejectedWith410AndAppendsNothing() {
        InviteId inviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        Clock muchLaterClock = Clock.fixed(FIXED_NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC);
        assertThatThrownBy(() -> handler(muchLaterClock)
                        .handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteExpiredApplicationException.class);
        int eventsAfterFirstExpiry = eventStore.readStream(streamId).size();

        assertThatThrownBy(() -> handler(Clock.fixed(FIXED_NOW.plus(Duration.ofDays(9)), ZoneOffset.UTC))
                        .handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteExpiredApplicationException.class);

        assertThat(eventStore.readStream(streamId)).hasSize(eventsAfterFirstExpiry);
    }

    @Test
    void anUnknownInviteIsRejectedWith404AndAppendsNothing() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());

        assertThatThrownBy(() -> handler().handle(
                        "anna-sub", householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteNotFoundApplicationException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void aConsumedInviteAcceptedByANonMemberIsRejectedWith409() {
        InviteId inviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());

        assertThatThrownBy(() -> handler().handle(
                        "carla-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteAlreadyConsumedApplicationException.class);
    }

    @Test
    void aNotFoundAcceptLeavesNoMemberMappingForTheCaller() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());

        assertThatThrownBy(() -> handler().handle(
                        "stranger-sub", householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteNotFoundApplicationException.class);

        assertThat(mappingRepository.findMemberId(new KeycloakUserId("stranger-sub"), householdId)).isEmpty();
    }

    @Test
    void anExpiredAcceptLeavesNoMemberMappingForTheCaller() {
        InviteId inviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        Clock muchLaterClock = Clock.fixed(FIXED_NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC);

        assertThatThrownBy(() -> handler(muchLaterClock).handle(
                        "expired-caller-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteExpiredApplicationException.class);

        assertThat(mappingRepository.findMemberId(new KeycloakUserId("expired-caller-sub"), householdId)).isEmpty();
    }

    @Test
    void anAlreadyConsumedAcceptLeavesNoMemberMappingForTheStranger() {
        InviteId inviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());

        assertThatThrownBy(() -> handler().handle(
                        "carla-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteAlreadyConsumedApplicationException.class);

        assertThat(mappingRepository.findMemberId(new KeycloakUserId("carla-sub"), householdId)).isEmpty();
    }

    @Test
    void aRetryWithTheSameCommandIdConvergesViaTheDomainsAcceptedNoOp() {
        // Not an EventStore-dedup test: by the retry, the invite is already ACCEPTED and the
        // joiner already a member, so Household.acceptInvite() no-ops (raises nothing) and append
        // is never called a second time — the convergence comes from that domain-level idempotency.
        InviteId inviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        CommandId commandId = CommandId.generate();

        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), commandId.toString());
        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), commandId.toString());

        assertThat(eventStore.readStream(streamId)).hasSize(5);
    }

    @Test
    void aSuccessPathAppendConflictRetractsTheFreshMappingSoTheLosingCallerKeepsNoAccess() {
        // A concurrent redemption of the same bearer invite advances the stream between rehydrate
        // and append: this caller's acceptInvite succeeds on its stale snapshot and persists a
        // fresh mapping, but the append then loses the race. The compensating retract must leave no
        // durable mapping — otherwise the 409-rejected loser would keep household access (F1).
        InviteId inviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        AcceptInviteHandler handler = new AcceptInviteHandler(
                new AppendConflictingEventStore(eventStore),
                issueMemberIdentity,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                alwaysConsentingGate());

        assertThatThrownBy(() -> handler.handle(
                        "loser-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(ConcurrencyConflictException.class);

        assertThat(mappingRepository.findMemberId(new KeycloakUserId("loser-sub"), householdId)).isEmpty();
    }

    @Test
    void anAlreadyMemberWhoseAppendConflictsKeepsTheirRealMapping() {
        // Anna is already a member; a second invite's accept conflicts on append. Her mapping is
        // NOT freshly provisioned, so the compensation must NOT delete her real membership.
        InviteId firstInviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        handler().handle("anna-sub", householdId.toString(), firstInviteId.toString(), CommandId.generate().toString());
        Household forSecondInvite = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion versionBeforeSecondInvite = forSecondInvite.version();
        InviteId secondInviteId = InviteId.generate();
        forSecondInvite.invitePerson(adminMemberId, secondInviteId, FIXED_NOW, CommandId.generate());
        eventStore.append(versionBeforeSecondInvite, forSecondInvite.uncommittedEvents(), CommandId.generate());
        MemberId annaMemberId = issueMemberIdentity.provision("anna-sub", householdId).memberId();

        AcceptInviteHandler handler = new AcceptInviteHandler(
                new AppendConflictingEventStore(eventStore),
                issueMemberIdentity,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                alwaysConsentingGate());
        assertThatThrownBy(() -> handler.handle(
                        "anna-sub", householdId.toString(), secondInviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(ConcurrencyConflictException.class);

        assertThat(mappingRepository.findMemberId(new KeycloakUserId("anna-sub"), householdId)).contains(annaMemberId);
    }

    @Test
    void aLazyExpiryAppendConflictStillSurfaces410AndLeavesNoMappingForTheExpiredCaller() {
        // The housekeeping append that persists the lazy InviteExpired loses a concurrency race.
        // The caller must still see 410 (AC3) — never the raw 409 concurrency conflict — and, since
        // the expiry path never persists, gains no mapping.
        InviteId inviteId = seedHouseholdWithAPendingInvite(FIXED_NOW);
        AcceptInviteHandler handler = new AcceptInviteHandler(
                new AppendConflictingEventStore(eventStore),
                issueMemberIdentity,
                Clock.fixed(FIXED_NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC),
                alwaysConsentingGate());

        assertThatThrownBy(() -> handler.handle(
                        "expired-caller-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteExpiredApplicationException.class);

        assertThat(mappingRepository.findMemberId(new KeycloakUserId("expired-caller-sub"), householdId)).isEmpty();
    }

    /** {@code EventStore} that delegates reads but rejects every {@code append} with a
     * {@link ConcurrencyConflictException} — simulating a concurrent writer that advanced the stream
     * between the handler's rehydrate and its append (AD-8). */
    private static final class AppendConflictingEventStore implements EventStore {
        private final EventStore delegate;

        private AppendConflictingEventStore(EventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
            throw new ConcurrencyConflictException(expectedVersion, expectedVersion);
        }

        @Override
        public List<DomainEvent> readStream(StreamId streamId) {
            return delegate.readStream(streamId);
        }
    }
}
