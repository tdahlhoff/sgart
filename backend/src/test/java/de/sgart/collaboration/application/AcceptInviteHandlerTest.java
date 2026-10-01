package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AcceptInviteHandler;
import de.sgart.collaboration.application.exception.ConsentRequiredException;
import de.sgart.collaboration.application.exception.InviteNotFoundApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.IssueMemberIdentity;
import de.sgart.identity.application.MemberMappingConflictException;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the accept command path (Story 4.2/8.4): a valid code appends
 * {@code MemberJoined}, an already-member joiner is a no-op (nothing appended), an unknown or
 * replaced invite id is rejected (404) with nothing appended, and a same-{@code commandId} retry
 * converges via the event store's own dedup.
 */
class AcceptInviteHandlerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-06T10:00:00Z");

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final IssueMemberIdentity issueMemberIdentity = new IssueMemberIdentity(mappingRepository);

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private AcceptInviteHandler handler() {
        return new AcceptInviteHandler(eventStore, issueMemberIdentity, alwaysConsentingGate());
    }

    private static ConsentGate alwaysConsentingGate() {
        return keycloakUserId -> true;
    }

    /** Seeds a household with its first active invite code (from {@code create}) and returns it. */
    private InviteId seedHouseholdWithActiveInvite() {
        InviteId inviteId = InviteId.generate();
        Household household = Household.create(
                householdId, new HouseholdName("Familie Muster"), adminMemberId, inviteId, FIXED_NOW, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        return inviteId;
    }

    @Test
    void acceptInvite_withoutRecordedConsent_isRejectedWith409ConsentRequired() {
        InviteId inviteId = seedHouseholdWithActiveInvite();
        AcceptInviteHandler handler = new AcceptInviteHandler(eventStore, issueMemberIdentity, never -> false);

        assertThatThrownBy(() -> handler.handle(
                        "anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(ConsentRequiredException.class);

        assertThat(eventStore.readStream(streamId)).hasSize(3);
        assertThat(mappingRepository.findMemberId(new KeycloakUserId("anna-sub"), householdId)).isEmpty();
    }

    @Test
    void acceptingTheActiveCodeAppendsMemberJoined() {
        InviteId inviteId = seedHouseholdWithActiveInvite();

        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(4);
        assertThat(events.get(3)).isInstanceOf(MemberJoined.class);
    }

    @Test
    void theSameActiveCodeMayBeAcceptedByManyDifferentPeople() {
        InviteId inviteId = seedHouseholdWithActiveInvite();

        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());
        handler().handle("bruno-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());
        handler().handle("carla-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events.stream().filter(MemberJoined.class::isInstance)).hasSize(4); // admin + 3 joiners
    }

    @Test
    void acceptInvite_whenCallerAlreadyMember_isAConvergentNoOp() {
        InviteId inviteId = seedHouseholdWithActiveInvite();
        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());
        int eventsAfterFirstAccept = eventStore.readStream(streamId).size();

        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(eventsAfterFirstAccept);
    }

    @Test
    void anUnknownInviteIsRejectedWith404AndAppendsNothing() {
        Household household = Household.create(
                householdId,
                new HouseholdName("Familie Muster"),
                adminMemberId,
                InviteId.generate(),
                FIXED_NOW,
                CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());

        assertThatThrownBy(() -> handler().handle(
                        "anna-sub", householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteNotFoundApplicationException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(3);
    }

    @Test
    void aReplacedInviteIsRejectedWith404AndAppendsNothing() {
        InviteId originalInviteId = seedHouseholdWithActiveInvite();
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion versionBeforeReplace = household.version();
        household.replaceInviteCode(adminMemberId, InviteId.generate(), FIXED_NOW, CommandId.generate());
        eventStore.append(versionBeforeReplace, household.uncommittedEvents(), CommandId.generate());
        int eventsAfterReplace = eventStore.readStream(streamId).size();

        assertThatThrownBy(() -> handler().handle(
                        "anna-sub", householdId.toString(), originalInviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteNotFoundApplicationException.class);

        assertThat(eventStore.readStream(streamId)).hasSize(eventsAfterReplace);
    }

    @Test
    void aNotFoundAcceptLeavesNoMemberMappingForTheCaller() {
        Household household = Household.create(
                householdId,
                new HouseholdName("Familie Muster"),
                adminMemberId,
                InviteId.generate(),
                FIXED_NOW,
                CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());

        assertThatThrownBy(() -> handler().handle(
                        "stranger-sub", householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString()))
                .isInstanceOf(InviteNotFoundApplicationException.class);

        assertThat(mappingRepository.findMemberId(new KeycloakUserId("stranger-sub"), householdId)).isEmpty();
    }

    @Test
    void aRetryWithTheSameCommandIdConvergesViaTheDomainsAlreadyMemberNoOp() {
        // Not an EventStore-dedup test: by the retry, the joiner is already a member, so
        // Household.acceptInvite() no-ops (raises nothing) and append is never called a second
        // time — the convergence comes from that domain-level idempotency.
        InviteId inviteId = seedHouseholdWithActiveInvite();
        CommandId commandId = CommandId.generate();

        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), commandId.toString());
        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), commandId.toString());

        assertThat(eventStore.readStream(streamId)).hasSize(4);
    }

    @Test
    void aSuccessPathAppendConflictRetractsTheFreshMappingSoTheLosingCallerKeepsNoAccess() {
        // A concurrent write advances the stream between rehydrate and append: this caller's
        // acceptInvite succeeds on its stale snapshot and persists a fresh mapping, but the append
        // then loses the race. The compensating retract must leave no durable mapping — otherwise
        // the 409-rejected loser would keep household access (F1).
        InviteId inviteId = seedHouseholdWithActiveInvite();
        AcceptInviteHandler handler = new AcceptInviteHandler(
                new AppendConflictingEventStore(eventStore), issueMemberIdentity, alwaysConsentingGate());

        assertThatThrownBy(() -> handler.handle(
                        "loser-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(ConcurrencyConflictException.class);

        assertThat(mappingRepository.findMemberId(new KeycloakUserId("loser-sub"), householdId)).isEmpty();
    }

    @Test
    void anAlreadyMemberAcceptingAgainNeverAppendsSoAnAppendConflictingStoreIsNeverEvenCalled() {
        // Anna is already a member; re-accepting the still-active code is a pure domain no-op
        // (acceptInvite raises nothing), so the handler never reaches append at all — unlike the
        // old per-invite-consumption model, this path cannot hit the append-conflict/retract race,
        // and her real mapping is trivially undisturbed.
        InviteId inviteId = seedHouseholdWithActiveInvite();
        handler().handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());
        MemberId annaMemberId = issueMemberIdentity.provision("anna-sub", householdId).memberId();

        AcceptInviteHandler handler = new AcceptInviteHandler(
                new AppendConflictingEventStore(eventStore), issueMemberIdentity, alwaysConsentingGate());
        handler.handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());

        assertThat(mappingRepository.findMemberId(new KeycloakUserId("anna-sub"), householdId)).contains(annaMemberId);
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

    @Test
    void aConcurrentAcceptByTheSamePersonThatLosesTheMappingRaceAppendsNothingAndKeepsTheWinnersMapping() {
        InviteId inviteId = seedHouseholdWithActiveInvite();
        MemberId winnersMemberId = MemberId.generate();
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        mappingRepository.save(new MemberMapping(householdId, winnersMemberId, anna));
        IssueMemberIdentity staleReadingIssue = new IssueMemberIdentity(new StaleFirstReadRepository(mappingRepository));
        AcceptInviteHandler losingHandler = new AcceptInviteHandler(eventStore, staleReadingIssue, alwaysConsentingGate());

        assertThatThrownBy(() -> losingHandler.handle(
                        "anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(MemberMappingConflictException.class);

        assertThat(eventStore.readStream(streamId)).hasSize(3);
        assertThat(mappingRepository.findMemberId(anna, householdId)).contains(winnersMemberId);
    }

    @Test
    void aFailedWinnerAppendKeepsTheMappingWhenARetryOfTheSamePersonAlreadyJoinedWithTheSameId() {
        InviteId inviteId = seedHouseholdWithActiveInvite();
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        EventStore retryCommitsFirst = new EventStore() {
            private boolean retryHasRun;

            @Override
            public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
                if (!retryHasRun) {
                    retryHasRun = true;
                    handler().handle("anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString());
                }
                eventStore.append(expectedVersion, events, commandId);
            }

            @Override
            public List<DomainEvent> readStream(StreamId stream) {
                return eventStore.readStream(stream);
            }
        };
        AcceptInviteHandler winningHandler = new AcceptInviteHandler(retryCommitsFirst, issueMemberIdentity, alwaysConsentingGate());

        assertThatThrownBy(() -> winningHandler.handle(
                        "anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(ConcurrencyConflictException.class);

        assertThat(mappingRepository.findMemberId(anna, householdId)).isPresent();
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        assertThat(household.isMember(mappingRepository.findMemberId(anna, householdId).orElseThrow())).isTrue();
    }

    @Test
    void aFailedAppendStillRetractsTheFreshMappingWhenNobodyJoinedWithIt() {
        InviteId inviteId = seedHouseholdWithActiveInvite();
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        EventStore alwaysConflicting = new EventStore() {
            @Override
            public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
                throw new ConcurrencyConflictException(expectedVersion, expectedVersion);
            }

            @Override
            public List<DomainEvent> readStream(StreamId stream) {
                return eventStore.readStream(stream);
            }
        };
        AcceptInviteHandler failingHandler = new AcceptInviteHandler(alwaysConflicting, issueMemberIdentity, alwaysConsentingGate());

        assertThatThrownBy(() -> failingHandler.handle(
                        "anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(ConcurrencyConflictException.class);

        assertThat(mappingRepository.findMemberId(anna, householdId)).isEmpty();
    }

    @Test
    void aFailedAppendRetractsTheFreshMappingWhenTheCompensationReReadFails() {
        InviteId inviteId = seedHouseholdWithActiveInvite();
        KeycloakUserId anna = new KeycloakUserId("anna-sub");
        EventStore failingAfterTheFirstRead = new EventStore() {
            private int reads;

            @Override
            public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
                throw new ConcurrencyConflictException(expectedVersion, expectedVersion);
            }

            @Override
            public List<DomainEvent> readStream(StreamId stream) {
                if (reads++ > 0) {
                    throw new IllegalStateException("event store unavailable");
                }
                return eventStore.readStream(stream);
            }
        };
        AcceptInviteHandler failingHandler =
                new AcceptInviteHandler(failingAfterTheFirstRead, issueMemberIdentity, alwaysConsentingGate());

        assertThatThrownBy(() -> failingHandler.handle(
                        "anna-sub", householdId.toString(), inviteId.toString(), CommandId.generate().toString()))
                .isInstanceOf(ConcurrencyConflictException.class);

        assertThat(mappingRepository.findMemberId(anna, householdId)).isEmpty();
    }

    /** Reports no mapping on the first lookup (the loser's stale read in {@code provision}), then the real state. */
    private static final class StaleFirstReadRepository implements MemberMappingRepository {

        private final MemberMappingRepository delegate;
        private final AtomicBoolean hasServedStaleRead = new AtomicBoolean(false);

        StaleFirstReadRepository(MemberMappingRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<MemberId> findMemberId(KeycloakUserId keycloakUserId, HouseholdId householdId) {
            return hasServedStaleRead.getAndSet(true) ? delegate.findMemberId(keycloakUserId, householdId) : Optional.empty();
        }

        @Override
        public void save(MemberMapping mapping) {
            delegate.save(mapping);
        }

        @Override
        public void deleteMapping(KeycloakUserId keycloakUserId, HouseholdId householdId) {
            delegate.deleteMapping(keycloakUserId, householdId);
        }

        @Override
        public List<HouseholdId> householdIdsFor(KeycloakUserId keycloakUserId) {
            return delegate.householdIdsFor(keycloakUserId);
        }

        @Override
        public List<KeycloakUserId> keycloakUserIdsFor(HouseholdId householdId) {
            return delegate.keycloakUserIdsFor(householdId);
        }

        @Override
        public void deleteMappingByMember(HouseholdId householdId, MemberId memberId) {
            delegate.deleteMappingByMember(householdId, memberId);
        }

        @Override
        public void deleteAllMappings(HouseholdId householdId) {
            delegate.deleteAllMappings(householdId);
        }
    }
}
