package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.event.HouseholdDeleted;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.event.InviteRevoked;
import de.sgart.collaboration.domain.event.MemberDemoted;
import de.sgart.collaboration.domain.event.MemberInvited;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.MemberLeft;
import de.sgart.collaboration.domain.event.MemberPromoted;
import de.sgart.collaboration.domain.event.MemberRemoved;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.collaboration.domain.exception.DuplicateStoreNameException;
import de.sgart.collaboration.domain.exception.GovernanceNotPermittedException;
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
 * MemberJoined} carrying the caller-issued {@link MemberId}, then {@code MemberInvited} for the
 * household's first active invite code (Story 8.4), and replaying that history rebuilds identical
 * state (AC1, AC3).
 */
class HouseholdTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final InviteId inviteId = InviteId.generate();
    private final CommandId commandId = CommandId.generate();

    @Test
    void create_raisesHouseholdCreatedThenMemberJoinedThenMemberInvitedInOrder() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, inviteId, NOW, commandId);

        List<DomainEvent> events = household.uncommittedEvents();
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOf(HouseholdCreated.class);
        assertThat(events.get(1)).isInstanceOf(MemberJoined.class);
        assertThat(events.get(2)).isInstanceOf(MemberInvited.class);

        HouseholdCreated created = (HouseholdCreated) events.get(0);
        assertThat(created.householdId()).isEqualTo(householdId);
        assertThat(created.name()).isEqualTo(new HouseholdName("Familie Muster"));

        MemberJoined joined = (MemberJoined) events.get(1);
        assertThat(joined.householdId()).isEqualTo(householdId);
        assertThat(joined.memberId()).isEqualTo(adminMemberId);
        assertThat(joined.role()).isEqualTo(HouseholdRole.ADMIN);

        MemberInvited invited = (MemberInvited) events.get(2);
        assertThat(invited.inviteId()).isEqualTo(inviteId);
        assertThat(invited.invitedBy()).isEqualTo(adminMemberId);
        assertThat(invited.role()).isEqualTo(HouseholdRole.PARTICIPANT);
    }

    @Test
    void create_advancesTheVersionByThree() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, inviteId, NOW, commandId);

        StreamId streamId = StreamId.forHousehold(householdId);
        assertThat(household.version()).isEqualTo(AggregateVersion.of(streamId, 3));
    }

    @Test
    void create_yieldsExactlyOneActiveInviteCode() {
        Household household = createdHousehold();

        assertThat(household.activeInviteId()).isEqualTo(inviteId);
    }

    @Test
    void create_rejectsABlankOrWhitespaceName() {
        assertThatThrownBy(() -> new HouseholdName("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsANullHouseholdId() {
        assertThatThrownBy(() -> Household.create(
                        null, new HouseholdName("Familie Muster"), adminMemberId, inviteId, NOW, commandId))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void replayingTheCreationHistoryRebuildsIdenticalStateAndVersion() {
        Household original = createdHousehold();
        List<DomainEvent> history = original.uncommittedEvents();

        Household rehydrated = Household.rehydrate(StreamId.forHousehold(householdId), history);

        assertThat(rehydrated.householdId()).isEqualTo(original.householdId());
        assertThat(rehydrated.name()).isEqualTo(original.name());
        assertThat(rehydrated.activeInviteId()).isEqualTo(original.activeInviteId());
        assertThat(rehydrated.version()).isEqualTo(original.version());
    }

    @Test
    void anAdminRenamesTheHouseholdRaisingHouseholdRenamedWithTheNewName() {
        Household household = createdHousehold();
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
        Household household = createdHousehold();

        assertThatThrownBy(() ->
                        household.rename(strangerId, new HouseholdName("Familie Beispiel"), CommandId.generate()))
                .isInstanceOf(RenameNotPermittedException.class);
    }

    @Test
    void renamingToTheSameNameRaisesNoEvent() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        household.rename(adminMemberId, new HouseholdName("Familie Muster"), CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void renameFoldsSoThatSubsequentStateReflectsTheNewName() {
        Household household = createdHousehold();

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
    void noEventCarriesADisplayNameEmailOrKeycloakUserId() {
        assertNoPersonalDataComponent(HouseholdCreated.class);
        assertNoPersonalDataComponent(MemberJoined.class);
        assertNoPersonalDataComponent(HouseholdRenamed.class);
        assertNoPersonalDataComponent(StoreAdded.class);
        assertNoPersonalDataComponent(StoreArchived.class);
        assertNoPersonalDataComponent(MemberInvited.class);
        assertNoPersonalDataComponent(InviteRevoked.class);
        assertNoPersonalDataComponent(MemberLeft.class);
        assertNoPersonalDataComponent(MemberRemoved.class);
        assertNoPersonalDataComponent(MemberPromoted.class);
        assertNoPersonalDataComponent(MemberDemoted.class);
        assertNoPersonalDataComponent(HouseholdDeleted.class);
    }

    // --- Story 8.4: single, replaceable household invite code -------------------------------

    @Test
    void acceptInvite_withTheActiveCode_raisesMemberJoinedAsParticipant() {
        Household household = createdHousehold();
        household.markEventsCommitted();
        MemberId joiner = MemberId.generate();

        household.acceptInvite(inviteId, joiner, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(1);
        MemberJoined joined = (MemberJoined) household.uncommittedEvents().get(0);
        assertThat(joined.memberId()).isEqualTo(joiner);
        assertThat(joined.role()).isEqualTo(HouseholdRole.PARTICIPANT);
    }

    @Test
    void acceptInvite_withTheActiveCode_maySucceedForManyDifferentJoiners() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        household.acceptInvite(inviteId, MemberId.generate(), CommandId.generate());
        household.acceptInvite(inviteId, MemberId.generate(), CommandId.generate());
        household.acceptInvite(inviteId, MemberId.generate(), CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(3);
        assertThat(household.uncommittedEvents()).allMatch(MemberJoined.class::isInstance);
    }

    @Test
    void acceptInvite_bySomeoneAlreadyAMember_isAConvergentNoOp() {
        Household household = createdHousehold();
        MemberId joiner = MemberId.generate();
        household.acceptInvite(inviteId, joiner, CommandId.generate());
        household.markEventsCommitted();

        household.acceptInvite(inviteId, joiner, CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void acceptInvite_withAnUnknownCode_throwsInviteNotFound() {
        Household household = createdHousehold();
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.acceptInvite(InviteId.generate(), MemberId.generate(), CommandId.generate()))
                .isInstanceOf(InviteNotFoundException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void acceptInvite_withAReplacedCode_throwsInviteNotFound() {
        Household household = createdHousehold();
        household.replaceInviteCode(adminMemberId, InviteId.generate(), NOW, CommandId.generate());
        household.markEventsCommitted();

        assertThatThrownBy(() -> household.acceptInvite(inviteId, MemberId.generate(), CommandId.generate()))
                .isInstanceOf(InviteNotFoundException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
    }

    @Test
    void replaceInviteCode_byAnAdmin_invalidatesTheOldCodeAndIssuesTheNewOneInOneAppend() {
        Household household = createdHousehold();
        household.markEventsCommitted();
        InviteId newInviteId = InviteId.generate();

        household.replaceInviteCode(adminMemberId, newInviteId, NOW, CommandId.generate());

        List<DomainEvent> events = household.uncommittedEvents();
        assertThat(events).hasSize(2);
        InviteRevoked revoked = (InviteRevoked) events.get(0);
        assertThat(revoked.inviteId()).isEqualTo(inviteId);
        assertThat(revoked.revokedBy()).isEqualTo(adminMemberId);
        MemberInvited invited = (MemberInvited) events.get(1);
        assertThat(invited.inviteId()).isEqualTo(newInviteId);
        assertThat(household.activeInviteId()).isEqualTo(newInviteId);
    }

    @Test
    void replaceInviteCode_byAnyAdmin_isAllowedNotJustTheCreator() {
        MemberId secondAdminId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberInvited(EventId.generate(), householdId, inviteId, adminMemberId, HouseholdRole.PARTICIPANT, NOW),
                        new MemberJoined(EventId.generate(), householdId, secondAdminId, HouseholdRole.ADMIN)));

        household.replaceInviteCode(secondAdminId, InviteId.generate(), NOW, CommandId.generate());

        assertThat(household.uncommittedEvents()).hasSize(2);
    }

    @Test
    void replaceInviteCode_byAParticipant_throwsGovernanceNotPermitted() {
        MemberId participantId = MemberId.generate();
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId),
                List.of(
                        new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster")),
                        new MemberJoined(EventId.generate(), householdId, adminMemberId, HouseholdRole.ADMIN),
                        new MemberInvited(EventId.generate(), householdId, inviteId, adminMemberId, HouseholdRole.PARTICIPANT, NOW),
                        new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT)));

        assertThatThrownBy(() -> household.replaceInviteCode(participantId, InviteId.generate(), NOW, CommandId.generate()))
                .isInstanceOf(GovernanceNotPermittedException.class);
        assertThat(household.uncommittedEvents()).isEmpty();
        assertThat(household.activeInviteId()).isEqualTo(inviteId);
    }

    @Test
    void replaceInviteCode_retriedWithTheSameNewInviteId_isAConvergentNoOp() {
        Household household = createdHousehold();
        InviteId newInviteId = InviteId.generate();
        household.replaceInviteCode(adminMemberId, newInviteId, NOW, CommandId.generate());
        household.markEventsCommitted();

        household.replaceInviteCode(adminMemberId, newInviteId, NOW, CommandId.generate());

        assertThat(household.uncommittedEvents()).isEmpty();
        assertThat(household.activeInviteId()).isEqualTo(newInviteId);
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
        return Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, inviteId, NOW, commandId);
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
}
