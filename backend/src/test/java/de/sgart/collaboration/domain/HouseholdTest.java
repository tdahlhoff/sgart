package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.event.HouseholdDeleted;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.event.InviteAccepted;
import de.sgart.collaboration.domain.event.InviteExpired;
import de.sgart.collaboration.domain.event.InviteRevoked;
import de.sgart.collaboration.domain.event.MemberDemoted;
import de.sgart.collaboration.domain.event.MemberInvited;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.MemberLeft;
import de.sgart.collaboration.domain.event.MemberPromoted;
import de.sgart.collaboration.domain.event.MemberRemoved;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.collaboration.domain.exception.DuplicatePendingInviteException;
import de.sgart.collaboration.domain.exception.DuplicateStoreNameException;
import de.sgart.collaboration.domain.exception.GovernanceNotPermittedException;
import de.sgart.collaboration.domain.exception.InviteAlreadyConsumedException;
import de.sgart.collaboration.domain.exception.InviteExpiredException;
import de.sgart.collaboration.domain.exception.InviteNotFoundException;
import de.sgart.collaboration.domain.exception.LastAdminException;
import de.sgart.collaboration.domain.exception.NotAHouseholdMemberException;
import de.sgart.collaboration.domain.exception.RenameNotPermittedException;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * first real aggregate: creating a household raises {@code HouseholdCreated} then {@code
 * MemberJoined} carrying the caller-issued {@link MemberId}, and replaying that history rebuilds
 * identical state (AC1, AC3).
 */
class HouseholdTest {

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final CommandId commandId = CommandId.generate();

    @Test
    void create_raisesHouseholdCreatedThenMemberJoinedInOrderCarryingTheGivenMemberIdAndName() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, commandId);

        List<DomainEvent> events = household.uncommittedEvents();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(HouseholdCreated.class);
        assertThat(events.get(1)).isInstanceOf(MemberJoined.class);

        HouseholdCreated created = (HouseholdCreated) events.get(0);
        assertThat(created.householdId()).isEqualTo(householdId);
        assertThat(created.name()).isEqualTo(new HouseholdName("Familie Muster"));

        MemberJoined joined = (MemberJoined) events.get(1);
        assertThat(joined.householdId()).isEqualTo(householdId);
        assertThat(joined.memberId()).isEqualTo(adminMemberId);
        assertThat(joined.role()).isEqualTo(HouseholdRole.ADMIN);
    }

    @Test
    void create_advancesTheVersionByTwo() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, commandId);

        StreamId streamId = StreamId.forHousehold(householdId);
        assertThat(household.version()).isEqualTo(AggregateVersion.of(streamId, 2));
    }

    @Test
    void create_rejectsABlankOrWhitespaceName() {
        assertThatThrownBy(() -> new HouseholdName("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsANullHouseholdId() {
        assertThatThrownBy(() ->
                        Household.create(null, new HouseholdName("Familie Muster"), adminMemberId, commandId))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void replayingHouseholdCreatedThenMemberJoinedRebuildsIdenticalStateAndVersion() {
        Household original =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, commandId);
        List<DomainEvent> history = original.uncommittedEvents();

        Household rehydrated = Household.rehydrate(StreamId.forHousehold(householdId), history);

        assertThat(rehydrated.householdId()).isEqualTo(original.householdId());
        assertThat(rehydrated.name()).isEqualTo(original.name());
        assertThat(rehydrated.version()).isEqualTo(original.version());
    }

    @Test
    void anAdminRenamesTheHouseholdRaisingHouseholdRenamedWithTheNewName() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, commandId);
        household.markEventsCommitted();

        household.rename(adminMemberId, new HouseholdName("Familie Beispiel"), CommandId.generate());

        List<DomainEvent> events = household.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(HouseholdRenamed.class);
        HouseholdRenamed renamed = (HouseholdRenamed) events.get(0);
        assertThat(renamed.householdId()).isEqualTo(householdId);
        assertThat(renamed.newName()).isEqualTo(new HouseholdName("Familie Beispiel"));
    }

    @Test
    void aParticipantCannotRenameTheHousehold() {
        MemberId participantId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT)));

        assertThatThrownBy(() ->
                        household.rename(participantId, new HouseholdName("Familie Beispiel"), CommandId.generate()))
                .isInstanceOf(RenameNotPermittedException.class);
    }

    @Test
    void aNonMemberCannotRenameTheHousehold() {
        MemberId strangerId = MemberId.generate();
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, commandId);

        assertThatThrownBy(() ->
                        household.rename(strangerId, new HouseholdName("Familie Beispiel"), CommandId.generate()))
                .isInstanceOf(RenameNotPermittedException.class);
    }

    @Test
    void renamingToTheSameNameRaisesNoEvent() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, commandId);
        household.markEventsCommitted();

        household.rename(adminMemberId, new HouseholdName("Familie Muster"), CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void renameFoldsSoThatSubsequentStateReflectsTheNewName() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, commandId);

        household.rename(adminMemberId, new HouseholdName("Familie Beispiel"), CommandId.generate());

        assertThat(household.name()).isEqualTo(new HouseholdName("Familie Beispiel"));
    }

    @Test
    void addStore_raisesStoreAddedCarryingTheStoreIdNameAndChain() {
        Household household = createdHousehold();
        household.markEventsCommitted();
        StoreId storeId = StoreId.generate();
        StoreChainId chainId = StoreChainId.generate();

        household.addStore(adminMemberId, storeId, new StoreName("Edeka Schiedemann"), chainId, CommandId.generate());

        List<DomainEvent> events = household.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StoreAdded.class);
        StoreAdded added = (StoreAdded) events.get(0);
        assertThat(added.householdId()).isEqualTo(householdId);
        assertThat(added.storeId()).isEqualTo(storeId);
        assertThat(added.name()).isEqualTo(new StoreName("Edeka Schiedemann"));
        assertThat(added.chainId()).isEqualTo(chainId);
    }

    @Test
    void addStore_raisesStoreAddedWithNoChainWhenTheChainIsCleared() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        household.addStore(adminMemberId, StoreId.generate(), new StoreName("Wochenmarkt"), null, CommandId.generate());

        StoreAdded added = (StoreAdded) household.uncommittedEvents().get(0);
        assertThat(added.chainId()).isNull();
    }

    @Test
    void addStore_rejectsANameThatDuplicatesAnActiveStoreCaseInsensitively() {
        Household household = createdHousehold();
        household.addStore(adminMemberId, StoreId.generate(), new StoreName("Edeka"), null, CommandId.generate());
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.addStore(
                        adminMemberId, StoreId.generate(), new StoreName("  edeka  "), null, CommandId.generate()))
                .isInstanceOf(DuplicateStoreNameException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void addStore_allowsReAddingANameAfterItsStoreWasArchived() {
        Household household = createdHousehold();
        StoreId firstStoreId = StoreId.generate();
        household.addStore(adminMemberId, firstStoreId, new StoreName("Edeka"), null, CommandId.generate());
        household.archiveStore(adminMemberId, firstStoreId, CommandId.generate());
        household.markEventsCommitted();

        household.addStore(adminMemberId, StoreId.generate(), new StoreName("Edeka"), null, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        assertThat(household.uncommittedEvents().get(0)).isInstanceOf(StoreAdded.class);
    }

    @Test
    void archiveStore_raisesStoreArchivedForAnActiveStore() {
        Household household = createdHousehold();
        StoreId storeId = StoreId.generate();
        household.addStore(adminMemberId, storeId, new StoreName("Edeka"), null, CommandId.generate());
        household.markEventsCommitted();

        household.archiveStore(adminMemberId, storeId, CommandId.generate());

        List<DomainEvent> events = household.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StoreArchived.class);
        assertThat(((StoreArchived) events.get(0)).storeId()).isEqualTo(storeId);
    }

    @Test
    void archiveStore_isASilentNoOpForAnAlreadyArchivedStore() {
        Household household = createdHousehold();
        StoreId storeId = StoreId.generate();
        household.addStore(adminMemberId, storeId, new StoreName("Edeka"), null, CommandId.generate());
        household.archiveStore(adminMemberId, storeId, CommandId.generate());
        household.markEventsCommitted();

        household.archiveStore(adminMemberId, storeId, CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void archiveStore_isASilentNoOpForAnUnknownStore() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        household.archiveStore(adminMemberId, StoreId.generate(), CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void addStore_rejectsANonMember() {
        Household household = createdHousehold();
        MemberId strangerId = MemberId.generate();

        assertThatThrownBy(() -> household.addStore(
                        strangerId, StoreId.generate(), new StoreName("Edeka"), null, CommandId.generate()))
                .isInstanceOf(NotAHouseholdMemberException.class);
    }

    @Test
    void addStore_isNotAdminGatedSoAParticipantMemberSucceeds() {
        MemberId participantId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT)));

        household.addStore(participantId, StoreId.generate(), new StoreName("Edeka"), null, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        assertThat(household.uncommittedEvents().get(0)).isInstanceOf(StoreAdded.class);
    }

    @Test
    void invitePerson_isNotAdminGatedSoAParticipantMemberSucceeds() {
        MemberId participantId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT)));

        household.invitePerson(
                participantId, InviteId.generate(), new EmailHmac("hmac-1"), Instant.now(), CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        assertThat(household.uncommittedEvents().get(0)).isInstanceOf(MemberInvited.class);
    }

    @Test
    void noEventCarriesADisplayNameEmailOrKeycloakUserId() {
        assertNoPersonalDataComponent(HouseholdCreated.class);
        assertNoPersonalDataComponent(MemberJoined.class);
        assertNoPersonalDataComponent(HouseholdRenamed.class);
        assertNoPersonalDataComponent(StoreAdded.class);
        assertNoPersonalDataComponent(StoreArchived.class);
        assertNoPersonalDataComponent(InviteExpired.class);
        assertNoPersonalDataComponent(InviteAccepted.class);
        assertNoPersonalDataComponent(InviteRevoked.class);
        assertNoPersonalDataComponent(MemberLeft.class);
        assertNoPersonalDataComponent(MemberRemoved.class);
        assertNoPersonalDataComponent(MemberPromoted.class);
        assertNoPersonalDataComponent(MemberDemoted.class);
        assertNoPersonalDataComponent(HouseholdDeleted.class);
    }

    @Test
    void invitePerson_raisesMemberInvitedCarryingTheEmailHmacNotTheEmail() {
        Household household = createdHousehold();
        household.markEventsCommitted();
        InviteId inviteId = InviteId.generate();
        EmailHmac emailHmac = new EmailHmac("hmac-of-anna-example-com");
        Instant now = Instant.parse("2026-09-06T10:00:00Z");

        household.invitePerson(adminMemberId, inviteId, emailHmac, now, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        MemberInvited invited = (MemberInvited) household.uncommittedEvents().get(0);
        assertThat(invited.inviteId()).isEqualTo(inviteId);
        assertThat(invited.emailHmac()).isEqualTo(emailHmac);
        assertThat(invited.invitedBy()).isEqualTo(adminMemberId);
        assertThat(invited.role()).isEqualTo(HouseholdRole.PARTICIPANT);
        assertThat(invited.invitedAt()).isEqualTo(now);
        assertNoRawEmailComponent(MemberInvited.class);
    }

    @Test
    void invitePerson_byAnyMember_isAllowed() {
        MemberId participantId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT)));

        household.invitePerson(
                participantId,
                InviteId.generate(),
                new EmailHmac("hmac-1"),
                Instant.parse("2026-09-06T10:00:00Z"),
                CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        assertThat(household.uncommittedEvents().get(0)).isInstanceOf(MemberInvited.class);
    }

    @Test
    void invitePerson_byNonMember_throwsNotAHouseholdMember() {
        Household household = createdHousehold();
        MemberId strangerId = MemberId.generate();

        assertThatThrownBy(() -> household.invitePerson(
                        strangerId,
                        InviteId.generate(),
                        new EmailHmac("hmac-1"),
                        Instant.parse("2026-09-06T10:00:00Z"),
                        CommandId.generate()))
                .isInstanceOf(NotAHouseholdMemberException.class);
    }

    @Test
    void invitePerson_withANonExpiredPendingInviteToTheSameEmail_throwsDuplicatePendingInvite() {
        Household household = createdHousehold();
        EmailHmac emailHmac = new EmailHmac("hmac-1");
        Instant firstInviteAt = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, InviteId.generate(), emailHmac, firstInviteAt, CommandId.generate());

        assertThatThrownBy(() -> household.invitePerson(
                        adminMemberId,
                        InviteId.generate(),
                        emailHmac,
                        firstInviteAt.plusSeconds(60),
                        CommandId.generate()))
                .isInstanceOf(DuplicatePendingInviteException.class);
    }

    @Test
    void invitePerson_withAPastTtlPendingInviteToTheSameEmail_raisesInviteExpiredThenMemberInvited() {
        Household household = createdHousehold();
        EmailHmac emailHmac = new EmailHmac("hmac-1");
        InviteId staleInviteId = InviteId.generate();
        Instant firstInviteAt = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, staleInviteId, emailHmac, firstInviteAt, CommandId.generate());
        household.markEventsCommitted();

        Instant pastTtl = firstInviteAt.plus(Invite.TIME_TO_LIVE).plusSeconds(1);
        InviteId newInviteId = InviteId.generate();
        household.invitePerson(adminMemberId, newInviteId, emailHmac, pastTtl, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(2);
        assertThat(household.uncommittedEvents().get(0)).isInstanceOf(InviteExpired.class);
        assertThat(((InviteExpired) household.uncommittedEvents().get(0)).inviteId()).isEqualTo(staleInviteId);
        assertThat(household.uncommittedEvents().get(1)).isInstanceOf(MemberInvited.class);
        assertThat(((MemberInvited) household.uncommittedEvents().get(1)).inviteId()).isEqualTo(newInviteId);
    }

    @Test
    void invitePerson_toADifferentEmail_isAllowedAlongsideAnExistingPendingInvite() {
        Household household = createdHousehold();
        Instant now = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, InviteId.generate(), new EmailHmac("hmac-1"), now, CommandId.generate());
        household.markEventsCommitted();

        household.invitePerson(adminMemberId, InviteId.generate(), new EmailHmac("hmac-2"), now, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        assertThat(household.uncommittedEvents().get(0)).isInstanceOf(MemberInvited.class);
    }

    @Test
    void acceptInvite_onAPendingInTtlInvite_raisesInviteAcceptedAndMemberJoinedAsParticipant() {
        Household household = createdHousehold();
        InviteId inviteId = InviteId.generate();
        Instant invitedAt = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, inviteId, new EmailHmac("hmac-1"), invitedAt, CommandId.generate());
        household.markEventsCommitted();
        MemberId joiner = MemberId.generate();

        household.acceptInvite(inviteId, joiner, invitedAt.plusSeconds(60), CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(2);
        InviteAccepted accepted = (InviteAccepted) household.uncommittedEvents().get(0);
        assertThat(accepted.inviteId()).isEqualTo(inviteId);
        assertThat(accepted.memberId()).isEqualTo(joiner);
        MemberJoined joined = (MemberJoined) household.uncommittedEvents().get(1);
        assertThat(joined.memberId()).isEqualTo(joiner);
        assertThat(joined.role()).isEqualTo(HouseholdRole.PARTICIPANT);
    }

    @Test
    void acceptInvite_bySomeoneAlreadyAMember_raisesInviteAcceptedButNoSecondMemberJoined() {
        MemberId existingMember = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, existingMember, HouseholdRole.PARTICIPANT)));
        InviteId inviteId = InviteId.generate();
        Instant invitedAt = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, inviteId, new EmailHmac("hmac-1"), invitedAt, CommandId.generate());
        household.markEventsCommitted();

        household.acceptInvite(inviteId, existingMember, invitedAt.plusSeconds(60), CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        assertThat(household.uncommittedEvents().get(0)).isInstanceOf(InviteAccepted.class);
    }

    @Test
    void acceptInvite_onAPastTtlPendingInvite_raisesInviteExpiredThenThrowsInviteExpired() {
        Household household = createdHousehold();
        InviteId inviteId = InviteId.generate();
        Instant invitedAt = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, inviteId, new EmailHmac("hmac-1"), invitedAt, CommandId.generate());
        household.markEventsCommitted();
        Instant pastTtl = invitedAt.plus(Invite.TIME_TO_LIVE).plusSeconds(1);

        assertThatThrownBy(() -> household.acceptInvite(
                        inviteId, MemberId.generate(), pastTtl, CommandId.generate()))
                .isInstanceOf(InviteExpiredException.class);

        assertThat(household.uncommittedEvents()).hasSize(1);
        assertThat(household.uncommittedEvents().get(0)).isInstanceOf(InviteExpired.class);
        assertThat(((InviteExpired) household.uncommittedEvents().get(0)).inviteId()).isEqualTo(inviteId);
    }

    @Test
    void acceptInvite_onAnAlreadyExpiredInvite_throwsInviteExpiredWithoutANewEvent() {
        Household household = createdHousehold();
        InviteId inviteId = InviteId.generate();
        Instant invitedAt = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, inviteId, new EmailHmac("hmac-1"), invitedAt, CommandId.generate());
        household.markEventsCommitted();
        Instant pastTtl = invitedAt.plus(Invite.TIME_TO_LIVE).plusSeconds(1);
        assertThatThrownBy(() -> household.acceptInvite(inviteId, MemberId.generate(), pastTtl, CommandId.generate()))
                .isInstanceOf(InviteExpiredException.class);
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.acceptInvite(
                        inviteId, MemberId.generate(), pastTtl.plusSeconds(60), CommandId.generate()))
                .isInstanceOf(InviteExpiredException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void acceptInvite_onAnUnknownInvite_throwsInviteNotFound() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.acceptInvite(
                        InviteId.generate(), MemberId.generate(), Instant.now(), CommandId.generate()))
                .isInstanceOf(InviteNotFoundException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void acceptInvite_reAcceptedByTheSameJoiner_isAConvergentNoOp() {
        Household household = createdHousehold();
        InviteId inviteId = InviteId.generate();
        Instant invitedAt = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, inviteId, new EmailHmac("hmac-1"), invitedAt, CommandId.generate());
        household.markEventsCommitted();
        MemberId joiner = MemberId.generate();
        household.acceptInvite(inviteId, joiner, invitedAt.plusSeconds(60), CommandId.generate());
        household.markEventsCommitted();

        household.acceptInvite(inviteId, joiner, invitedAt.plusSeconds(120), CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void acceptInvite_onAConsumedInviteByANonMember_throwsInviteAlreadyConsumed() {
        Household household = createdHousehold();
        InviteId inviteId = InviteId.generate();
        Instant invitedAt = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, inviteId, new EmailHmac("hmac-1"), invitedAt, CommandId.generate());
        household.markEventsCommitted();
        household.acceptInvite(inviteId, MemberId.generate(), invitedAt.plusSeconds(60), CommandId.generate());
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.acceptInvite(
                        inviteId, MemberId.generate(), invitedAt.plusSeconds(120), CommandId.generate()))
                .isInstanceOf(InviteAlreadyConsumedException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void leaveHousehold_byAParticipant_raisesMemberLeft() {
        MemberId participantId = MemberId.generate();
        Household household = householdWithAdminAndParticipant(participantId);
        household.markEventsCommitted();

        household.leaveHousehold(participantId, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        MemberLeft left = (MemberLeft) household.uncommittedEvents().get(0);
        assertThat(left.householdId()).isEqualTo(householdId);
        assertThat(left.memberId()).isEqualTo(participantId);
    }

    @Test
    void leaveHousehold_byTheLastAdmin_throwsLastAdmin() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.leaveHousehold(adminMemberId, CommandId.generate()))
                .isInstanceOf(LastAdminException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void leaveHousehold_byANonMember_isAConvergentNoOp() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        household.leaveHousehold(MemberId.generate(), CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void removeMember_byAnAdmin_raisesMemberRemovedAndDropsTheirRole() {
        MemberId participantId = MemberId.generate();
        Household household = householdWithAdminAndParticipant(participantId);
        household.markEventsCommitted();

        household.removeMember(adminMemberId, participantId, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        MemberRemoved removed = (MemberRemoved) household.uncommittedEvents().get(0);
        assertThat(removed.memberId()).isEqualTo(participantId);
        assertThat(removed.removedBy()).isEqualTo(adminMemberId);

        // The removed member's role is folded away — they can no longer act as a member at all.
        assertThatThrownBy(() -> household.addStore(
                        participantId, StoreId.generate(), new StoreName("Edeka"), null, CommandId.generate()))
                .isInstanceOf(NotAHouseholdMemberException.class);
    }

    @Test
    void removeMember_byAParticipant_throwsGovernanceNotPermitted() {
        MemberId participantId = MemberId.generate();
        MemberId anotherParticipantId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT),
                        new MemberJoined(
                                EventId.generate(), householdId, anotherParticipantId, HouseholdRole.PARTICIPANT)));

        assertThatThrownBy(() ->
                        household.removeMember(participantId, anotherParticipantId, CommandId.generate()))
                .isInstanceOf(GovernanceNotPermittedException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void removeMember_aSelfTarget_throwsGovernanceNotPermittedEvenForTheOnlyAdmin() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.removeMember(adminMemberId, adminMemberId, CommandId.generate()))
                .isInstanceOf(GovernanceNotPermittedException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void removeMember_aSelfTarget_throwsGovernanceNotPermittedEvenForACoAdmin() {
        MemberId coAdminId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, coAdminId, HouseholdRole.ADMIN)));
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.removeMember(coAdminId, coAdminId, CommandId.generate()))
                .isInstanceOf(GovernanceNotPermittedException.class);
        assertThat(household.uncommittedEvents()).isEmpty();

        // A co-Admin removing the OTHER Admin stays allowed.
        household.removeMember(adminMemberId, coAdminId, CommandId.generate());
        assertThat(household.uncommittedEvents()).hasSize(1);
        assertThat(household.uncommittedEvents().get(0)).isInstanceOf(MemberRemoved.class);
    }

    @Test
    void isMember_reflectsTheFoldedRoleMap() {
        MemberId participantId = MemberId.generate();
        Household household = householdWithAdminAndParticipant(participantId);

        assertThat(household.isMember(adminMemberId)).isTrue();
        assertThat(household.isMember(participantId)).isTrue();
        assertThat(household.isMember(MemberId.generate())).isFalse();

        household.removeMember(adminMemberId, participantId, CommandId.generate());

        assertThat(household.isMember(participantId)).isFalse();
    }

    @Test
    void removeMember_aNonMemberTarget_isAConvergentNoOp() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        household.removeMember(adminMemberId, MemberId.generate(), CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void promoteMember_byAnAdmin_raisesMemberPromoted() {
        MemberId participantId = MemberId.generate();
        Household household = householdWithAdminAndParticipant(participantId);
        household.markEventsCommitted();

        household.promoteMember(adminMemberId, participantId, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        MemberPromoted promoted = (MemberPromoted) household.uncommittedEvents().get(0);
        assertThat(promoted.memberId()).isEqualTo(participantId);
        assertThat(promoted.promotedBy()).isEqualTo(adminMemberId);
    }

    @Test
    void promoteMember_anAlreadyAdmin_isAConvergentNoOp() {
        MemberId secondAdminId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, secondAdminId, HouseholdRole.ADMIN)));

        household.promoteMember(adminMemberId, secondAdminId, CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void promoteMember_byAParticipant_throwsGovernanceNotPermitted() {
        MemberId participantId = MemberId.generate();
        MemberId anotherParticipantId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT),
                        new MemberJoined(
                                EventId.generate(), householdId, anotherParticipantId, HouseholdRole.PARTICIPANT)));

        assertThatThrownBy(() ->
                        household.promoteMember(participantId, anotherParticipantId, CommandId.generate()))
                .isInstanceOf(GovernanceNotPermittedException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void demoteMember_byAnAdmin_raisesMemberDemoted() {
        MemberId secondAdminId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, secondAdminId, HouseholdRole.ADMIN)));

        household.demoteMember(adminMemberId, secondAdminId, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        MemberDemoted demoted = (MemberDemoted) household.uncommittedEvents().get(0);
        assertThat(demoted.memberId()).isEqualTo(secondAdminId);
        assertThat(demoted.demotedBy()).isEqualTo(adminMemberId);
    }

    @Test
    void demoteMember_theOnlyAdmin_throwsLastAdmin() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.demoteMember(adminMemberId, adminMemberId, CommandId.generate()))
                .isInstanceOf(LastAdminException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void demoteMember_anAlreadyParticipant_isAConvergentNoOp() {
        MemberId participantId = MemberId.generate();
        Household household = householdWithAdminAndParticipant(participantId);
        household.markEventsCommitted();

        household.demoteMember(adminMemberId, participantId, CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void revokeInvite_aPendingInvite_raisesInviteRevoked() {
        Household household = createdHousehold();
        InviteId inviteId = InviteId.generate();
        Instant invitedAt = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, inviteId, new EmailHmac("hmac-1"), invitedAt, CommandId.generate());
        household.markEventsCommitted();

        household.revokeInvite(adminMemberId, inviteId, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        InviteRevoked revoked = (InviteRevoked) household.uncommittedEvents().get(0);
        assertThat(revoked.inviteId()).isEqualTo(inviteId);
        assertThat(revoked.revokedBy()).isEqualTo(adminMemberId);
    }

    @Test
    void revokeInvite_byAParticipant_throwsGovernanceNotPermitted() {
        MemberId participantId = MemberId.generate();
        Household household = householdWithAdminAndParticipant(participantId);
        InviteId inviteId = InviteId.generate();
        household.invitePerson(
                adminMemberId, inviteId, new EmailHmac("hmac-1"), Instant.parse("2026-09-06T10:00:00Z"), CommandId.generate());
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.revokeInvite(participantId, inviteId, CommandId.generate()))
                .isInstanceOf(GovernanceNotPermittedException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void revokeInvite_anAbsentInvite_throwsInviteNotFound() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.revokeInvite(adminMemberId, InviteId.generate(), CommandId.generate()))
                .isInstanceOf(InviteNotFoundException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void revokeInvite_anAlreadyRevokedInvite_isAConvergentNoOp() {
        Household household = createdHousehold();
        InviteId inviteId = InviteId.generate();
        household.invitePerson(
                adminMemberId, inviteId, new EmailHmac("hmac-1"), Instant.parse("2026-09-06T10:00:00Z"), CommandId.generate());
        household.revokeInvite(adminMemberId, inviteId, CommandId.generate());
        household.markEventsCommitted();

        household.revokeInvite(adminMemberId, inviteId, CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void revokeInvite_anAcceptedInvite_throwsInviteNotFound() {
        Household household = createdHousehold();
        InviteId inviteId = InviteId.generate();
        Instant invitedAt = Instant.parse("2026-09-06T10:00:00Z");
        household.invitePerson(adminMemberId, inviteId, new EmailHmac("hmac-1"), invitedAt, CommandId.generate());
        household.acceptInvite(inviteId, MemberId.generate(), invitedAt.plusSeconds(60), CommandId.generate());
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.revokeInvite(adminMemberId, inviteId, CommandId.generate()))
                .isInstanceOf(InviteNotFoundException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void isDeleted_reflectsHouseholdDeletedFold() {
        Household household = createdHousehold();

        assertThat(household.isDeleted()).isFalse();

        household.deleteHousehold(adminMemberId, CommandId.generate());

        assertThat(household.isDeleted()).isTrue();
    }

    @Test
    void deleteHousehold_byAnAdmin_raisesHouseholdDeleted() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        household.deleteHousehold(adminMemberId, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        HouseholdDeleted deleted = (HouseholdDeleted) household.uncommittedEvents().get(0);
        assertThat(deleted.householdId()).isEqualTo(householdId);
        assertThat(deleted.deletedBy()).isEqualTo(adminMemberId);
    }

    @Test
    void deleteHousehold_byAParticipant_throwsGovernanceNotPermitted() {
        MemberId participantId = MemberId.generate();
        Household household = householdWithAdminAndParticipant(participantId);
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.deleteHousehold(participantId, CommandId.generate()))
                .isInstanceOf(GovernanceNotPermittedException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void deleteHousehold_anAlreadyDeletedHousehold_isAConvergentNoOp() {
        Household household = createdHousehold();
        household.deleteHousehold(adminMemberId, CommandId.generate());
        household.markEventsCommitted();

        household.deleteHousehold(adminMemberId, CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void deleteHousehold_anAlreadyDeletedHousehold_staysAConvergentNoOpEvenForACallerWhoIsNoLongerAdmin() {
        // The no-op check runs before requireAdmin, so a retry after a concurrent role change (e.g.
        // the retrying caller was demoted between an earlier successful append and a failed de-link)
        // still converges instead of throwing — the self-heal retract downstream still runs.
        MemberId participantId = MemberId.generate();
        Household household = householdWithAdminAndParticipant(participantId);
        household.deleteHousehold(adminMemberId, CommandId.generate());
        household.markEventsCommitted();

        household.deleteHousehold(participantId, CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    private Household householdWithAdminAndParticipant(MemberId participantId) {
        return Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT)));
    }

    private Household createdHousehold() {
        return Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, commandId);
    }

    private void assertNoPersonalDataComponent(Class<? extends DomainEvent> eventType) {
        List<String> componentNames = Arrays.stream(eventType.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        assertThat(componentNames)
                .noneMatch(name -> name.contains("displayname")
                        || name.contains("email")
                        || name.contains("keycloak"));
    }

    /**
     * {@code MemberInvited} is the one event allowed to carry an email-*named* component — but only
     * the {@code emailHmac} digest, never the raw address (AD-6). Distinct from {@link
     * #assertNoPersonalDataComponent}, which bans the substring "email" outright: here a component
     * literally named/containing "email" is only acceptable if it is exactly {@code emailHmac}.
     */
    private void assertNoRawEmailComponent(Class<? extends DomainEvent> eventType) {
        List<String> componentNames = Arrays.stream(eventType.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        assertThat(componentNames).noneMatch(name -> name.contains("email") && !name.equals("emailhmac"));
        assertThat(componentNames).contains("emailHmac".toLowerCase(Locale.ROOT));
    }
}
