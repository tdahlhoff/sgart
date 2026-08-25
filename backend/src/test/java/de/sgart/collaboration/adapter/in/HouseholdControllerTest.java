package de.sgart.collaboration.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.application.command.CreateHouseholdHandler;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import java.util.UUID;
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
 * MockMvc slice over the real {@code HouseholdController}/{@code CreateHouseholdHandler} wiring,
 * with the durable adapters swapped for in-memory doubles ({@link InMemoryEventStore}, {@link
 * InMemoryMemberMappingRepository}) — no live KurrentDB/PostgreSQL needed (Story 1.4 Dev Notes).
 * Proves AC1/AC2 end-to-end through the REST layer: create/list, unauthenticated rejection,
 * blank-name mapping to {@code {code}}, and that the caller identity comes only from the JWT
 * {@code sub} (the request body carries no identity field at all).
 */
@SpringBootTest
@AutoConfigureMockMvc
class HouseholdControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private MemberMappingRepository mappingRepository;

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
    }

    @Test
    void create_returns201WithTheNewHouseholdIdForAnAuthenticatedCaller() throws Exception {
        mockMvc.perform(post("/api/v1/households")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody("Familie Muster")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.householdId").isNotEmpty());
    }

    @Test
    void create_rejectsABlankNameWith400AndALocalizableCode() throws Exception {
        mockMvc.perform(post("/api/v1/households")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("household.nameRequired"));
    }

    @Test
    void create_rejectsAMissingCommandIdWith400AndALocalizableCodeNotA500() throws Exception {
        mockMvc.perform(post("/api/v1/households")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Familie Muster\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.commandIdRequired"));
    }

    @Test
    void create_rejectsAMalformedCommandIdWith400AndALocalizableCodeNotA500() throws Exception {
        mockMvc.perform(post("/api/v1/households")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Familie Muster\",\"commandId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.commandIdInvalid"));
    }

    @Test
    void create_rejectsAMissingNameWith400AndALocalizableCodeNotA500() throws Exception {
        mockMvc.perform(post("/api/v1/households")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("household.nameRequired"));
    }

    @Test
    void create_rejectsAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(post("/api/v1/households")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody("Familie Muster")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_returns200WithAnEmptyArrayForACallerWithNoHouseholds() throws Exception {
        mockMvc.perform(get("/api/v1/households").with(jwt().jwt(jwt -> jwt.subject("stranger-sub"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void list_rejectsAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/households")).andExpect(status().isUnauthorized());
    }

    @Test
    void create_derivesTheCallerIdentityOnlyFromTheJwtSubjectNeverFromTheRequestBody() throws Exception {
        // The request body carries no identity field at all (CreateHouseholdRequest has none) —
        // two different JWT subjects independently minting into their own household proves
        // identity resolution is genuinely sub-only, not something a client could spoof (AR10).
        String firstResponse = mockMvc.perform(post("/api/v1/households")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody("Familie Muster")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String secondResponse = mockMvc.perform(post("/api/v1/households")
                        .with(jwt().jwt(jwt -> jwt.subject("ben-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody("WG Sonnenallee")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(firstResponse).isNotEqualTo(secondResponse);
    }

    @Test
    void patchRenamesAndReturns204() throws Exception {
        HouseholdId householdId = HouseholdId.generate();
        MemberId adminMemberId = MemberId.generate();
        seedHousehold(householdId, adminMemberId, "Familie Muster", "anna-sub");

        mockMvc.perform(patch("/api/v1/households/{householdId}", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameRequestBody("Familie Beispiel")))
                .andExpect(status().isNoContent());
    }

    @Test
    void patchWithABlankNameReturns400WithNameRequired() throws Exception {
        HouseholdId householdId = HouseholdId.generate();
        MemberId adminMemberId = MemberId.generate();
        seedHousehold(householdId, adminMemberId, "Familie Muster", "anna-sub");

        mockMvc.perform(patch("/api/v1/households/{householdId}", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameRequestBody("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("household.nameRequired"));
    }

    @Test
    void patchFromANonAdminReturns403WithRenameNotPermitted() throws Exception {
        HouseholdId householdId = HouseholdId.generate();
        MemberId adminMemberId = MemberId.generate();
        seedHousehold(householdId, adminMemberId, "Familie Muster", "anna-sub");
        // A synthetic Participant member (invites are Epic 4; today only the domain test path can
        // produce a non-Admin) seeded into the stream and the ACL so the resolved member is a
        // Participant the aggregate rejects.
        MemberId participantMemberId = MemberId.generate();
        eventStore.append(
                AggregateVersion.of(StreamId.forHousehold(householdId), 2),
                List.of(new MemberJoined(
                        EventId.generate(), householdId, participantMemberId, HouseholdRole.PARTICIPANT)),
                CommandId.generate());
        mappingRepository.save(
                new MemberMapping(householdId, participantMemberId, new KeycloakUserId("participant-sub")));

        mockMvc.perform(patch("/api/v1/households/{householdId}", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("participant-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameRequestBody("Familie Beispiel")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("household.renameNotPermitted"));
    }

    @Test
    void patchWithAMalformedCommandIdReturns400() throws Exception {
        HouseholdId householdId = HouseholdId.generate();
        MemberId adminMemberId = MemberId.generate();
        seedHousehold(householdId, adminMemberId, "Familie Muster", "anna-sub");

        mockMvc.perform(patch("/api/v1/households/{householdId}", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Familie Beispiel\",\"commandId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.commandIdInvalid"));
    }

    /** Seeds a household stream (creator = {@code adminMemberId}) and the caller's ACL mapping. */
    private void seedHousehold(
            HouseholdId householdId, MemberId adminMemberId, String name, String keycloakUserId) {
        Household household =
                Household.create(householdId, new HouseholdName(name), adminMemberId, CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                household.uncommittedEvents(),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(keycloakUserId)));
    }

    private static String createRequestBody(String name) {
        return """
                {"name":"%s","commandId":"%s"}
                """.formatted(name, UUID.randomUUID());
    }

    private static String renameRequestBody(String name) {
        return """
                {"name":"%s","commandId":"%s"}
                """.formatted(name, UUID.randomUUID());
    }
}
