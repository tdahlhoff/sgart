package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.ReplaceInviteCodeHandler;
import de.sgart.collaboration.application.exception.GovernanceNotPermittedApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the replace command path (Story 8.4, F7): an Admin's call
 * invalidates the old code and issues a fresh one in one append, a Participant/non-member is
 * rejected with nothing appended, and a same-{@code commandId} retry converges via the {@code
 * EventStore}'s own commandId dedup — not the domain's newInviteId-equality no-op (see {@link
 * #retryingWithTheSameCommandIdConvergesViaTheEventStoresOwnDedup()} for why that distinction
 * matters).
 */
class ReplaceInviteCodeHandlerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-06T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final ResolveMemberIdentity resolveMemberIdentity = new ResolveMemberIdentity(mappingRepository);

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private ReplaceInviteCodeHandler handler(EventStore store) {
        return new ReplaceInviteCodeHandler(store, resolveMemberIdentity, FIXED_CLOCK);
    }

    private ReplaceInviteCodeHandler handler() {
        return handler(eventStore);
    }

    /** Seeds a household with its first active invite code and an Admin mapped to {@code anna-sub}. */
    private void seedHouseholdWithAdmin() {
        InviteId inviteId = InviteId.generate();
        Household household = Household.create(
                householdId, new HouseholdName("Familie Muster"), adminMemberId, inviteId, FIXED_NOW, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.seed(new MemberMapping(householdId, adminMemberId, new KeycloakUserId("anna-sub")));
    }

    private Household rehydrate() {
        return Household.rehydrate(streamId, eventStore.readStream(streamId));
    }

    @Test
    void handle_byAnAdminInvalidatesTheOldCodeAndIssuesTheNewOneInOneAppend() {
        seedHouseholdWithAdmin();
        InviteId newInviteId = InviteId.generate();
        int eventsBeforeReplace = eventStore.readStream(streamId).size();

        handler().handle("anna-sub", householdId.toString(), newInviteId.toString(), CommandId.generate().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(eventsBeforeReplace + 2);
        assertThat(rehydrate().activeInviteId()).isEqualTo(newInviteId);
    }

    @Test
    void handle_byAParticipantIsRejectedWithGovernanceNotPermittedAndAppendsNothing() {
        seedHouseholdWithAdmin();
        MemberId participantMemberId = MemberId.generate();
        eventStore.append(
                AggregateVersion.of(streamId, eventStore.readStream(streamId).size()),
                List.of(new MemberJoined(EventId.generate(), householdId, participantMemberId, HouseholdRole.PARTICIPANT)),
                CommandId.generate());
        mappingRepository.seed(new MemberMapping(householdId, participantMemberId, new KeycloakUserId("participant-sub")));
        int eventsBeforeReplace = eventStore.readStream(streamId).size();

        assertThatThrownBy(() -> handler().handle(
                        "participant-sub", householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString()))
                .isInstanceOf(GovernanceNotPermittedApplicationException.class);

        assertThat(eventStore.readStream(streamId)).hasSize(eventsBeforeReplace);
    }

    @Test
    void handle_byANonMemberIsRejectedWithNotAMember() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() -> handler().handle(
                        "stranger-sub", householdId.toString(), InviteId.generate().toString(), CommandId.generate().toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    /**
     * A same-{@code commandId} retry must converge because the {@code EventStore} itself discards a
     * duplicate append — the same guarantee {@code CreateHousehold}'s Javadoc describes. This is
     * deliberately NOT the same thing as {@code Household.replaceInviteCode}'s own
     * newInviteId-equality no-op: an ordinary handler.handle() retry against a real store already
     * reflects the first call's committed state by the time it rehydrates, so the domain's no-op
     * alone would mask whether the store's dedup ever actually ran. To isolate the store's own
     * dedup, this test rehydrates from a view frozen BEFORE the first call — so the domain does NOT
     * see its own prior write and computes a genuinely fresh (non-no-op) append on the retry — and
     * proves that append is still swallowed by the store because the commandId was already applied.
     */
    @Test
    void retryingWithTheSameCommandIdConvergesViaTheEventStoresOwnDedup() {
        seedHouseholdWithAdmin();
        StaleReadEventStore staleReadStore = new StaleReadEventStore(eventStore, streamId);
        ReplaceInviteCodeHandler handlerOverStaleReads = handler(staleReadStore);
        InviteId newInviteId = InviteId.generate();
        CommandId commandId = CommandId.generate();

        handlerOverStaleReads.handle("anna-sub", householdId.toString(), newInviteId.toString(), commandId.toString());
        int eventsAfterFirstAttempt = eventStore.readStream(streamId).size();

        // The stale view still shows the pre-replace state, so this retry's domain call computes a
        // fresh (non-no-op) InviteRevoked/MemberInvited pair again — yet the append converges.
        handlerOverStaleReads.handle("anna-sub", householdId.toString(), newInviteId.toString(), commandId.toString());

        assertThat(eventStore.readStream(streamId)).hasSize(eventsAfterFirstAttempt);
        assertThat(rehydrate().activeInviteId()).isEqualTo(newInviteId);
    }

    /** {@code EventStore} whose {@code readStream} is frozen at construction time (simulating a
     * caller whose rehydrate never observes its own prior write) while {@code append} delegates to
     * the real store — isolates the store's commandId dedup from the domain's own idempotency. */
    private static final class StaleReadEventStore implements EventStore {
        private final EventStore delegate;
        private final List<DomainEvent> frozenView;

        private StaleReadEventStore(EventStore delegate, StreamId streamId) {
            this.delegate = delegate;
            this.frozenView = delegate.readStream(streamId);
        }

        @Override
        public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
            delegate.append(expectedVersion, events, commandId);
        }

        @Override
        public List<DomainEvent> readStream(StreamId streamId) {
            return frozenView;
        }
    }
}
