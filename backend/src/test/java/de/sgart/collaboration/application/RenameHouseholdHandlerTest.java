package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRenamed;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.MemberJoined;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
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
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the rename command path (AC3, AC4): an Admin's rename appends
 * {@code HouseholdRenamed} under the loaded expected version, a non-Admin member and a non-member
 * are both rejected with the right error code/type, malformed fields map to their localizable
 * codes, and a no-change rename appends nothing (convergent no-op, AD-8).
 */
class RenameHouseholdHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final RenameHouseholdHandler handler =
            new RenameHouseholdHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private void seedHouseholdWithAdmin() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
    }

    @Test
    void renamingAppendsHouseholdRenamedUnderTheLoadedExpectedVersion() {
        seedHouseholdWithAdmin();

        handler.handle(ADMIN_SUB, householdId.toString(), "Familie Beispiel", CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOf(HouseholdRenamed.class);
        assertThat(((HouseholdRenamed) events.get(2)).newName()).isEqualTo(new HouseholdName("Familie Beispiel"));
    }

    @Test
    void rejectsARenameFromANonAdminMember() {
        seedHouseholdWithAdmin();
        MemberId participantId = MemberId.generate();
        eventStore.append(
                AggregateVersion.of(streamId, 2),
                List.of(new MemberJoined(EventId.generate(), householdId, participantId, HouseholdRole.PARTICIPANT)),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, participantId, new KeycloakUserId("participant-sub")));

        assertThatThrownBy(() -> handler.handle(
                        "participant-sub", householdId.toString(), "Neuer Name", CommandId.generate().toString()))
                .isInstanceOf(RenameNotPermittedApplicationException.class)
                .satisfies(thrown -> assertThat(
                                ((RenameNotPermittedApplicationException) thrown).errorDescriptor().code())
                        .isEqualTo("household.renameNotPermitted"));
        assertThat(eventStore.readStream(streamId)).hasSize(3); // no HouseholdRenamed appended
    }

    @Test
    void rejectsARenameFromANonMember() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), "Neuer Name", CommandId.generate().toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void mapsABlankNameToNameRequired() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() ->
                        handler.handle(ADMIN_SUB, householdId.toString(), "   ", CommandId.generate().toString()))
                .isInstanceOf(InvalidHouseholdNameException.class)
                .satisfies(thrown -> assertThat(((InvalidHouseholdNameException) thrown).errorDescriptor().code())
                        .isEqualTo("household.nameRequired"));
    }

    @Test
    void mapsAnOverLongNameToNameTooLong() {
        seedHouseholdWithAdmin();
        String tooLong = "x".repeat(HouseholdName.MAX_LENGTH + 1);

        assertThatThrownBy(() ->
                        handler.handle(ADMIN_SUB, householdId.toString(), tooLong, CommandId.generate().toString()))
                .isInstanceOf(InvalidHouseholdNameException.class)
                .satisfies(thrown -> assertThat(((InvalidHouseholdNameException) thrown).errorDescriptor().code())
                        .isEqualTo("household.nameTooLong"));
    }

    @Test
    void mapsAMalformedHouseholdIdToHouseholdIdInvalid() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() ->
                        handler.handle(ADMIN_SUB, "not-a-uuid", "Neuer Name", CommandId.generate().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.householdIdInvalid"));
    }

    @Test
    void renamingToTheCurrentNameAppendsNothing() {
        seedHouseholdWithAdmin();

        handler.handle(ADMIN_SUB, householdId.toString(), "Familie Muster", CommandId.generate().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(2); // still just the two creation events
    }
}
