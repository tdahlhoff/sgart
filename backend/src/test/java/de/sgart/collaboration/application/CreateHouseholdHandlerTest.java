package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.MemberJoined;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.MintMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the command's state change: the minted {@link MemberId}
 * threads into {@code MemberJoined}, and the handler returns the new {@link HouseholdId} (AC1,
 * AC3).
 */
class CreateHouseholdHandlerTest {

    private static final String RAW_KEYCLOAK_USER_ID = "anna-sub";

    @Test
    void handle_mintsAMemberIdThatFlowsIntoMemberJoinedAndReturnsTheNewHouseholdId() {
        InMemoryEventStore eventStore = new InMemoryEventStore();
        InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
        CreateHouseholdHandler handler =
                new CreateHouseholdHandler(eventStore, new MintMemberIdentity(mappingRepository));

        HouseholdId householdId =
                handler.handle(RAW_KEYCLOAK_USER_ID, "Familie Muster", CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(StreamId.forHousehold(householdId));
        assertThat(events).hasSize(2);
        MemberJoined memberJoined = (MemberJoined) events.get(1);
        assertThat(memberJoined.role()).isEqualTo(HouseholdRole.ADMIN);
        MemberId mintedMemberId = mappingRepository
                .findMemberId(new KeycloakUserId(RAW_KEYCLOAK_USER_ID), householdId)
                .orElseThrow();
        assertThat(memberJoined.memberId()).isEqualTo(mintedMemberId);
    }

    @Test
    void handle_aPersonCreatingASecondHouseholdGetsAFreshUnrelatedMemberId() {
        InMemoryEventStore eventStore = new InMemoryEventStore();
        InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
        CreateHouseholdHandler handler =
                new CreateHouseholdHandler(eventStore, new MintMemberIdentity(mappingRepository));

        HouseholdId firstHouseholdId =
                handler.handle(RAW_KEYCLOAK_USER_ID, "Familie Muster", CommandId.generate().toString());
        HouseholdId secondHouseholdId =
                handler.handle(RAW_KEYCLOAK_USER_ID, "WG Sonnenallee", CommandId.generate().toString());

        assertThat(firstHouseholdId).isNotEqualTo(secondHouseholdId);
        KeycloakUserId keycloakUserId = new KeycloakUserId(RAW_KEYCLOAK_USER_ID);
        MemberId memberIdInFirst = mappingRepository.findMemberId(keycloakUserId, firstHouseholdId).orElseThrow();
        MemberId memberIdInSecond = mappingRepository.findMemberId(keycloakUserId, secondHouseholdId).orElseThrow();
        assertThat(memberIdInFirst).isNotEqualTo(memberIdInSecond);
    }

    @Test
    void handle_rejectsABlankNameWithAClientLocalizableCode() {
        CreateHouseholdHandler handler = new CreateHouseholdHandler(
                new InMemoryEventStore(), new MintMemberIdentity(new InMemoryMemberMappingRepository()));

        assertThatThrownBy(() -> handler.handle(RAW_KEYCLOAK_USER_ID, "   ", CommandId.generate().toString()))
                .isInstanceOf(InvalidHouseholdNameException.class)
                .satisfies(thrown -> assertThat(((InvalidHouseholdNameException) thrown).errorDescriptor().code())
                        .isEqualTo("household.nameRequired"));
    }

    @Test
    void handle_rejectsAnOverLongNameWithItsOwnCodeNotNameRequired() {
        CreateHouseholdHandler handler = new CreateHouseholdHandler(
                new InMemoryEventStore(), new MintMemberIdentity(new InMemoryMemberMappingRepository()));
        String tooLong = "x".repeat(HouseholdName.MAX_LENGTH + 1);

        assertThatThrownBy(() -> handler.handle(RAW_KEYCLOAK_USER_ID, tooLong, CommandId.generate().toString()))
                .isInstanceOf(InvalidHouseholdNameException.class)
                .satisfies(thrown -> assertThat(((InvalidHouseholdNameException) thrown).errorDescriptor().code())
                        .isEqualTo("household.nameTooLong"));
    }

    @Test
    void handle_rejectsAMissingCommandIdWithAClientLocalizableCode() {
        CreateHouseholdHandler handler = new CreateHouseholdHandler(
                new InMemoryEventStore(), new MintMemberIdentity(new InMemoryMemberMappingRepository()));

        assertThatThrownBy(() -> handler.handle(RAW_KEYCLOAK_USER_ID, "Familie Muster", null))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.commandIdRequired"));
    }

    @Test
    void handle_rejectsAMalformedCommandIdWithAClientLocalizableCode() {
        CreateHouseholdHandler handler = new CreateHouseholdHandler(
                new InMemoryEventStore(), new MintMemberIdentity(new InMemoryMemberMappingRepository()));

        assertThatThrownBy(() -> handler.handle(RAW_KEYCLOAK_USER_ID, "Familie Muster", "not-a-uuid"))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.commandIdInvalid"));
    }

    @Test
    void handle_isIdempotentPerCommandId_aRetryConvergesOnOneHouseholdWithoutASecondMint() {
        InMemoryEventStore eventStore = new InMemoryEventStore();
        InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
        CreateHouseholdHandler handler =
                new CreateHouseholdHandler(eventStore, new MintMemberIdentity(mappingRepository));
        String commandId = CommandId.generate().toString();

        HouseholdId firstResult = handler.handle(RAW_KEYCLOAK_USER_ID, "Familie Muster", commandId);
        HouseholdId retryResult = handler.handle(RAW_KEYCLOAK_USER_ID, "Familie Muster", commandId);

        assertThat(retryResult).isEqualTo(firstResult);
        // The append no-ops on the replayed commandId (still exactly the 2 creation events)...
        assertThat(eventStore.readStream(StreamId.forHousehold(firstResult))).hasSize(2);
        // ...and the mint replayed the existing MemberId rather than minting a second.
        assertThat(mappingRepository.householdIdsFor(new KeycloakUserId(RAW_KEYCLOAK_USER_ID)))
                .containsExactly(firstResult);
    }
}
