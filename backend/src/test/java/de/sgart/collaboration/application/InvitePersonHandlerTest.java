package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.InvitePersonHandler;
import de.sgart.collaboration.application.exception.AlreadyAHouseholdMemberApplicationException;
import de.sgart.collaboration.application.exception.DuplicatePendingInviteApplicationException;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidInviteEmailApplicationException;
import de.sgart.collaboration.application.exception.NotAHouseholdMemberApplicationException;
import de.sgart.collaboration.domain.EmailHmac;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.InviteExpired;
import de.sgart.collaboration.domain.event.MemberInvited;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.FindHouseholdMemberByEmail;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL + in-memory side-store, no
 * framework or persistence (CLAUDE.md §6). Proves the invite command path (AC1–AC5): a member's
 * invite appends {@code MemberInvited} carrying the HMAC, an already-a-member email is rejected
 * (409, AC3/E5) with no append, a non-member is rejected (403), a duplicate pending invite is
 * rejected (409, AC2), a past-TTL pending invite is expired then re-invited (AC5) with a side-store
 * purge, and every write to the side-store happens only after a successful append.
 */
class InvitePersonHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";
    private static final Instant FIXED_NOW = Instant.parse("2026-09-06T10:00:00Z");

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final FakeInviteEmailSideStore sideStore =
            new FakeInviteEmailSideStore(() -> this.eventStore.readStream(this.streamId));
    private final FakeFindHouseholdMemberByEmail findHouseholdMemberByEmail = new FakeFindHouseholdMemberByEmail();
    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private InvitePersonHandler handler() {
        return new InvitePersonHandler(
                eventStore,
                new ResolveMemberIdentity(mappingRepository),
                findHouseholdMemberByEmail,
                new FakeInviteEmailHasher(),
                sideStore,
                fixedClock);
    }

    private void seedHouseholdWithAdmin() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
    }

    @Test
    void invitingAPersonAppendsMemberInvitedCarryingTheHmac() {
        seedHouseholdWithAdmin();

        handler().handle(ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), "anna@example.com",
                CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOf(MemberInvited.class);
        MemberInvited invited = (MemberInvited) events.get(2);
        assertThat(invited.emailHmac()).isEqualTo(FakeInviteEmailHasher.hashOf("anna@example.com"));
        assertThat(invited.invitedBy()).isEqualTo(adminMemberId);
    }

    @Test
    void invitingAPersonStoresTheRawEmailInTheSideStoreAfterAppend() {
        seedHouseholdWithAdmin();
        InviteId inviteId = InviteId.generate();

        handler().handle(ADMIN_SUB, householdId.toString(), inviteId.toString(), "anna@example.com",
                CommandId.generate().toString());

        assertThat(sideStore.findEmail(inviteId)).contains(NormalizedEmail.fromRaw("anna@example.com"));
        assertThat(sideStore.storeCallCount).isEqualTo(1);
        assertThat(sideStore.appendWasVisibleOnEveryStoreCall).isTrue();
    }

    @Test
    void rejectsAnInviteFromANonMemberWith403() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() -> handler().handle(
                        "stranger-sub", householdId.toString(), InviteId.generate().toString(), "anna@example.com",
                        CommandId.generate().toString()))
                .isInstanceOf(NotAMemberException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void rejectsAnAlreadyAHouseholdMemberEmailWith409AndAppendsNothing() {
        seedHouseholdWithAdmin();
        findHouseholdMemberByEmail.existingMemberFor("berta@example.com", MemberId.generate());

        assertThatThrownBy(() -> handler().handle(
                        ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), "berta@example.com",
                        CommandId.generate().toString()))
                .isInstanceOf(AlreadyAHouseholdMemberApplicationException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(2);
        assertThat(sideStore.storeCallCount).isZero();
    }

    @Test
    void translatesTheAggregateMembershipGuardIntoAnApplicationException() {
        seedHouseholdWithAdmin();
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId("ghost-sub")));

        assertThatThrownBy(() -> handler().handle(
                        "ghost-sub", householdId.toString(), InviteId.generate().toString(), "anna@example.com",
                        CommandId.generate().toString()))
                .isInstanceOf(NotAHouseholdMemberApplicationException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void mapsANonExpiredDuplicatePendingInviteToTheConflictCode() {
        seedHouseholdWithAdmin();
        handler().handle(ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), "anna@example.com",
                CommandId.generate().toString());

        assertThatThrownBy(() -> handler().handle(
                        ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), "anna@example.com",
                        CommandId.generate().toString()))
                .isInstanceOf(DuplicatePendingInviteApplicationException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(3); // only the first MemberInvited
    }

    @Test
    void aPastTtlPendingInviteIsExpiredThenTheNewInviteIsAppendedAndTheStaleSideStoreRowIsPurged() {
        seedHouseholdWithAdmin();
        InviteId staleInviteId = InviteId.generate();
        Clock pastClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        InvitePersonHandler firstHandler = new InvitePersonHandler(
                eventStore,
                new ResolveMemberIdentity(mappingRepository),
                findHouseholdMemberByEmail,
                new FakeInviteEmailHasher(),
                sideStore,
                pastClock);
        firstHandler.handle(ADMIN_SUB, householdId.toString(), staleInviteId.toString(), "anna@example.com",
                CommandId.generate().toString());
        assertThat(sideStore.findEmail(staleInviteId)).isPresent();

        Clock muchLaterClock = Clock.fixed(FIXED_NOW.plus(java.time.Duration.ofDays(8)), ZoneOffset.UTC);
        InvitePersonHandler laterHandler = new InvitePersonHandler(
                eventStore,
                new ResolveMemberIdentity(mappingRepository),
                findHouseholdMemberByEmail,
                new FakeInviteEmailHasher(),
                sideStore,
                muchLaterClock);
        laterHandler.handle(ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), "anna@example.com",
                CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(5);
        assertThat(events.get(3)).isInstanceOf(InviteExpired.class);
        assertThat(((InviteExpired) events.get(3)).inviteId()).isEqualTo(staleInviteId);
        assertThat(events.get(4)).isInstanceOf(MemberInvited.class);
        assertThat(sideStore.findEmail(staleInviteId)).isEmpty(); // purged
    }

    @Test
    void mapsABlankEmailToEmailRequired() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() -> handler().handle(
                        ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), "   ",
                        CommandId.generate().toString()))
                .isInstanceOf(InvalidInviteEmailApplicationException.class)
                .satisfies(thrown -> assertThat(((InvalidInviteEmailApplicationException) thrown).errorDescriptor().code())
                        .isEqualTo("invite.emailRequired"));
    }

    @Test
    void mapsAMalformedEmailToEmailInvalid() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() -> handler().handle(
                        ADMIN_SUB, householdId.toString(), InviteId.generate().toString(), "not-an-email",
                        CommandId.generate().toString()))
                .isInstanceOf(InvalidInviteEmailApplicationException.class)
                .satisfies(thrown -> assertThat(((InvalidInviteEmailApplicationException) thrown).errorDescriptor().code())
                        .isEqualTo("invite.emailInvalid"));
    }

    @Test
    void mapsAMalformedInviteIdToInviteIdInvalid() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() -> handler().handle(
                        ADMIN_SUB, householdId.toString(), "not-a-uuid", "anna@example.com",
                        CommandId.generate().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.inviteIdInvalid"));
    }

    /** A minimal, deterministic fake hasher — a fixed, reproducible mapping, never the real HMAC. */
    private static final class FakeInviteEmailHasher implements de.sgart.collaboration.application.InviteEmailHasher {
        @Override
        public EmailHmac hash(NormalizedEmail normalizedEmail) {
            return hashOf(normalizedEmail.value());
        }

        static EmailHmac hashOf(String email) {
            return new EmailHmac("fake-hmac-" + email);
        }
    }

    /** Fake ACL email-resolution seam (AC3/E5) — lets a test opt an email into "already a member". */
    private static final class FakeFindHouseholdMemberByEmail implements FindHouseholdMemberByEmail {
        private final Map<String, MemberId> existingMembersByEmail = new HashMap<>();

        void existingMemberFor(String email, MemberId memberId) {
            existingMembersByEmail.put(email, memberId);
        }

        @Override
        public Optional<MemberId> forHousehold(String email, HouseholdId householdId) {
            return Optional.ofNullable(existingMembersByEmail.get(email));
        }
    }

    /** In-memory {@code InviteEmailSideStore} double that proves the append-before-side-store
     * ordering (T13): on every {@code store()} call it re-reads the event stream through the given
     * supplier and checks that a {@code MemberInvited} for the same invite is already there — a
     * fake that only counted calls could never catch a handler that reordered the two writes. */
    private static final class FakeInviteEmailSideStore
            implements de.sgart.collaboration.application.InviteEmailSideStore {
        private final Map<InviteId, NormalizedEmail> emailsByInviteId = new HashMap<>();
        private final Supplier<List<DomainEvent>> currentStreamEvents;
        private int storeCallCount = 0;
        private boolean appendWasVisibleOnEveryStoreCall = true;

        FakeInviteEmailSideStore(Supplier<List<DomainEvent>> currentStreamEvents) {
            this.currentStreamEvents = currentStreamEvents;
        }

        @Override
        public void store(InviteId inviteId, NormalizedEmail email) {
            storeCallCount++;
            boolean appendAlreadyVisible = currentStreamEvents.get().stream()
                    .filter(MemberInvited.class::isInstance)
                    .map(MemberInvited.class::cast)
                    .anyMatch(memberInvited -> memberInvited.inviteId().equals(inviteId));
            appendWasVisibleOnEveryStoreCall = appendWasVisibleOnEveryStoreCall && appendAlreadyVisible;
            emailsByInviteId.put(inviteId, email);
        }

        @Override
        public void purge(InviteId inviteId) {
            emailsByInviteId.remove(inviteId);
        }

        @Override
        public Optional<NormalizedEmail> findEmail(InviteId inviteId) {
            return Optional.ofNullable(emailsByInviteId.get(inviteId));
        }
    }
}
