package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.readmodel.HouseholdMemberReadModel;
import de.sgart.collaboration.domain.readmodel.InviteReadModel;
import de.sgart.collaboration.domain.readmodel.InviteView;
import de.sgart.collaboration.domain.readmodel.MemberRoleView;
import de.sgart.identity.adapter.out.InMemoryAccountConsentRepository;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.AccountConsentRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc slice over the real {@code InviteController}/handler/{@code GetActiveInviteCode} wiring,
 * with the durable adapters swapped for in-memory doubles — no live KurrentDB/PostgreSQL. Proves
 * Story 8.4 end-to-end through REST: the household's one active code is returned to any member
 * with {@code canReplace} reflecting their role, an Admin may replace it, a Participant may not,
 * accepting the active code succeeds for any number of callers, and accepting a replaced/unknown
 * code is rejected 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InviteControllerTest {

    private static final String ADMIN_SUB = "anna-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private MemberMappingRepository mappingRepository;

    @Autowired
    private InMemoryInviteReadModel inviteReadModel;

    @Autowired
    private InMemoryHouseholdMemberReadModel householdMemberReadModel;

    @Autowired
    private AccountConsentRepository accountConsentRepository;

    @TestConfiguration
    static class InMemoryAdaptersConfig {

        @Bean
        @Primary
        EventStore testEventStore() {
            return new InMemoryEventStore();
        }

        @Bean
        @Primary
        MemberMappingRepository testMemberMappingRepository() {
            return new InMemoryMemberMappingRepository();
        }

        @Bean
        @Primary
        AccountConsentRepository testAccountConsentRepository() {
            return new InMemoryAccountConsentRepository();
        }

        @Bean
        @Primary
        InMemoryInviteReadModel testInviteReadModel() {
            return new InMemoryInviteReadModel();
        }

        @Bean
        @Primary
        InMemoryHouseholdMemberReadModel testHouseholdMemberReadModel() {
            return new InMemoryHouseholdMemberReadModel();
        }
    }

    /** A read model whose active code a test can preset, so GET never touches PostgreSQL. */
    static final class InMemoryInviteReadModel implements InviteReadModel {
        java.util.Optional<InviteView> activeInvite = java.util.Optional.empty();

        @Override
        public java.util.Optional<InviteView> activeInviteOf(HouseholdId householdId) {
            return activeInvite;
        }
    }

    /** A read model whose roster a test can preset, so {@code canReplace} never touches PostgreSQL. */
    static final class InMemoryHouseholdMemberReadModel implements HouseholdMemberReadModel {
        List<MemberRoleView> members = List.of();

        @Override
        public List<MemberRoleView> membersOf(HouseholdId householdId) {
            return members;
        }
    }

    /**
     * {@code accept_*} tests below exercise {@code AcceptInviteHandler}, which gates on recorded
     * consent (Story 7.4, AC3) — pre-record it for the joiners those tests use.
     * {@link #accept_withoutRecordedConsent_returns409ConsentRequired()} is the one test that
     * deliberately leaves a joiner unconsented.
     */
    @BeforeEach
    void recordConsentForTheJoinersTheseTestsUse() {
        InMemoryAccountConsentRepository repository = (InMemoryAccountConsentRepository) accountConsentRepository;
        repository.clear();
        for (String joiner : List.of("joiner-sub", "first-joiner-sub", "second-joiner-sub")) {
            repository.record(new KeycloakUserId(joiner), "2026-beta-1", Instant.now());
        }
    }

    private HouseholdId seedHouseholdWithAdmin() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId adminMemberId = MemberId.generate();
        Household household = Household.create(
                householdId,
                new HouseholdName("Familie Muster"),
                adminMemberId,
                InviteId.generate(),
                Instant.now(),
                CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                household.uncommittedEvents(),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
        return householdId;
    }

    @Test
    void activeInviteCode_returns200WithTheCodeAndCanReplaceTrueForAnAdmin() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        MemberId adminMemberId = mappingRepository.findMemberId(new KeycloakUserId(ADMIN_SUB), householdId).orElseThrow();
        InviteId inviteId = InviteId.generate();
        inviteReadModel.activeInvite = java.util.Optional.of(new InviteView(inviteId));
        householdMemberReadModel.members = List.of(new MemberRoleView(adminMemberId, HouseholdRole.ADMIN));

        mockMvc.perform(get("/api/v1/households/{householdId}/invite-code", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteId").value(inviteId.toString()))
                .andExpect(jsonPath("$.canReplace").value(true));
    }

    @Test
    void activeInviteCode_returnsCanReplaceFalseForAParticipant() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        InviteId inviteId = InviteId.generate();
        MemberId participantMemberId = MemberId.generate();
        inviteReadModel.activeInvite = java.util.Optional.of(new InviteView(inviteId));
        householdMemberReadModel.members =
                List.of(new MemberRoleView(participantMemberId, HouseholdRole.PARTICIPANT));
        mappingRepository.save(
                new MemberMapping(householdId, participantMemberId, new KeycloakUserId("participant-sub")));

        mockMvc.perform(get("/api/v1/households/{householdId}/invite-code", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("participant-sub"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canReplace").value(false));
    }

    @Test
    void activeInviteCode_returns404WhileTheProjectionHasNotCaughtUpYet() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        // The read model bean is shared across tests in this context — reset it explicitly rather
        // than relying on its default, so this test proves the 404 branch regardless of test order.
        inviteReadModel.activeInvite = java.util.Optional.empty();

        mockMvc.perform(get("/api/v1/households/{householdId}/invite-code", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB))))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeInviteCode_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(get("/api/v1/households/{householdId}/invite-code", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void replace_byAnAdminReturns200() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/invite-code/replace", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRequestBody(InviteId.generate().toString())))
                .andExpect(status().isOk());
    }

    @Test
    void replace_byAParticipantReturns403WithGovernanceNotPermitted() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        MemberId participantMemberId = MemberId.generate();
        eventStore.append(
                AggregateVersion.of(StreamId.forHousehold(householdId), 3),
                List.of(new MemberJoined(
                        EventId.generate(), householdId, participantMemberId, HouseholdRole.PARTICIPANT)),
                CommandId.generate());
        mappingRepository.save(
                new MemberMapping(householdId, participantMemberId, new KeycloakUserId("participant-sub")));

        mockMvc.perform(post("/api/v1/households/{householdId}/invite-code/replace", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("participant-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRequestBody(InviteId.generate().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("governance.notPermitted"));
    }

    @Test
    void accept_returns200ForTheActiveCode() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        InviteId inviteId = activeInviteIdOf(householdId);

        mockMvc.perform(post("/api/v1/households/{householdId}/invites/{inviteId}/accept",
                        householdId.toString(), inviteId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("joiner-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRequestBody()))
                .andExpect(status().isOk());
    }

    @Test
    void accept_theSameActiveCodeSucceedsForMultipleDifferentJoiners() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        InviteId inviteId = activeInviteIdOf(householdId);

        mockMvc.perform(post("/api/v1/households/{householdId}/invites/{inviteId}/accept",
                        householdId.toString(), inviteId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("first-joiner-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRequestBody()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/households/{householdId}/invites/{inviteId}/accept",
                        householdId.toString(), inviteId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("second-joiner-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRequestBody()))
                .andExpect(status().isOk());
    }

    @Test
    void accept_withoutRecordedConsent_returns409ConsentRequired() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        InviteId inviteId = activeInviteIdOf(householdId);

        mockMvc.perform(post("/api/v1/households/{householdId}/invites/{inviteId}/accept",
                        householdId.toString(), inviteId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("never-consented-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRequestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("consent.required"));
    }

    @Test
    void accept_returns404ForAnUnknownInvite() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/invites/{inviteId}/accept",
                        householdId.toString(), InviteId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject("joiner-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRequestBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("invite.notFound"))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void accept_returns404ForAReplacedInvite() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        InviteId originalInviteId = activeInviteIdOf(householdId);

        mockMvc.perform(post("/api/v1/households/{householdId}/invite-code/replace", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRequestBody(InviteId.generate().toString())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/households/{householdId}/invites/{inviteId}/accept",
                        householdId.toString(), originalInviteId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("joiner-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRequestBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("invite.notFound"));
    }

    private InviteId activeInviteIdOf(HouseholdId householdId) {
        Household household = Household.rehydrate(
                StreamId.forHousehold(householdId), eventStore.readStream(StreamId.forHousehold(householdId)));
        return household.activeInviteId();
    }

    private static String acceptRequestBody() {
        return """
                {"commandId":"%s"}
                """.formatted(UUID.randomUUID());
    }

    private static String replaceRequestBody(String newInviteId) {
        return """
                {"newInviteId":"%s","commandId":"%s"}
                """.formatted(newInviteId, UUID.randomUUID());
    }
}
