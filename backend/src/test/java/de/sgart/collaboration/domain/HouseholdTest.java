package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * first real aggregate: creating a household raises {@code HouseholdCreated} then {@code
 * MemberJoined} carrying the caller-minted {@link MemberId}, and replaying that history rebuilds
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
    void noEventCarriesADisplayNameEmailOrKeycloakUserId() {
        assertNoPersonalDataComponent(HouseholdCreated.class);
        assertNoPersonalDataComponent(MemberJoined.class);
        assertNoPersonalDataComponent(HouseholdRenamed.class);
        assertNoPersonalDataComponent(StoreAdded.class);
        assertNoPersonalDataComponent(StoreArchived.class);
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
}
