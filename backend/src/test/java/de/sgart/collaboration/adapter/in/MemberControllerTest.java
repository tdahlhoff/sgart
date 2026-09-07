package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.readmodel.HouseholdMemberReadModel;
import de.sgart.collaboration.domain.readmodel.MemberRoleView;
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
 * MockMvc slice over the real {@code MemberController}/handler wiring, with the durable adapters
 * swapped for in-memory doubles — no live KurrentDB/PostgreSQL. Proves AC2/AC3/AC4/AC8 end-to-end
 * through REST: roster ({@code 200}, no PII), leave ({@code 204}), remove ({@code 204}/{@code
 * 403}), promote/demote ({@code 204}/{@code 403}), and that no request/response ever carries an
 * email or display name.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemberControllerTest {

    private static final String ADMIN_SUB = "anna-sub";
    private static final String PARTICIPANT_SUB = "bob-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private MemberMappingRepository mappingRepository;

    @Autowired
    private InMemoryHouseholdMemberReadModel memberReadModel;

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
        InMemoryHouseholdMemberReadModel testHouseholdMemberReadModel() {
            return new InMemoryHouseholdMemberReadModel();
        }
    }

    /** A read model whose roster a test presets, so GET never touches PostgreSQL. */
    static final class InMemoryHouseholdMemberReadModel implements HouseholdMemberReadModel {
        List<MemberRoleView> members = List.of();

        @Override
        public List<MemberRoleView> membersOf(HouseholdId householdId) {
            return members;
        }
    }

    private HouseholdId seedHouseholdWithAdminAndParticipant(MemberId adminMemberId, MemberId participantMemberId) {
        HouseholdId householdId = HouseholdId.generate();
        Household household = Household.create(
                householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                household.uncommittedEvents(),
                CommandId.generate());
        eventStore.append(
                AggregateVersion.of(StreamId.forHousehold(householdId), 2),
                List.of(new MemberJoined(EventId.generate(), householdId, participantMemberId, HouseholdRole.PARTICIPANT)),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
        mappingRepository.save(new MemberMapping(householdId, participantMemberId, new KeycloakUserId(PARTICIPANT_SUB)));
        return householdId;
    }

    @Test
    void list_returns200WithTheRosterAndNoPii() throws Exception {
        MemberId adminMemberId = MemberId.generate();
        MemberId participantMemberId = MemberId.generate();
        HouseholdId householdId = seedHouseholdWithAdminAndParticipant(adminMemberId, participantMemberId);
        memberReadModel.members = List.of(
                new MemberRoleView(adminMemberId, HouseholdRole.ADMIN),
                new MemberRoleView(participantMemberId, HouseholdRole.PARTICIPANT));

        mockMvc.perform(get("/api/v1/households/{householdId}/members", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].memberId").value(adminMemberId.toString()))
                .andExpect(jsonPath("$[0].isSelf").value(true))
                .andExpect(jsonPath("$[1].isSelf").value(false));
    }

    @Test
    void leave_returns204() throws Exception {
        MemberId adminMemberId = MemberId.generate();
        MemberId participantMemberId = MemberId.generate();
        HouseholdId householdId = seedHouseholdWithAdminAndParticipant(adminMemberId, participantMemberId);

        mockMvc.perform(delete("/api/v1/households/{householdId}/members/me", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(PARTICIPANT_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isNoContent());
    }

    @Test
    void remove_byAnAdminReturns204() throws Exception {
        MemberId adminMemberId = MemberId.generate();
        MemberId participantMemberId = MemberId.generate();
        HouseholdId householdId = seedHouseholdWithAdminAndParticipant(adminMemberId, participantMemberId);

        mockMvc.perform(delete(
                        "/api/v1/households/{householdId}/members/{memberId}",
                        householdId.toString(),
                        participantMemberId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isNoContent());
    }

    @Test
    void remove_byAParticipantReturns403WithGovernanceNotPermitted() throws Exception {
        MemberId adminMemberId = MemberId.generate();
        MemberId participantMemberId = MemberId.generate();
        HouseholdId householdId = seedHouseholdWithAdminAndParticipant(adminMemberId, participantMemberId);

        mockMvc.perform(delete(
                        "/api/v1/households/{householdId}/members/{memberId}",
                        householdId.toString(),
                        adminMemberId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(PARTICIPANT_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("governance.notPermitted"));
    }

    @Test
    void remove_aSelfTargetReturns403WithGovernanceNotPermitted() throws Exception {
        MemberId adminMemberId = MemberId.generate();
        HouseholdId householdId = HouseholdId.generate();
        Household household = Household.create(
                householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                household.uncommittedEvents(),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));

        mockMvc.perform(delete(
                        "/api/v1/households/{householdId}/members/{memberId}",
                        householdId.toString(),
                        adminMemberId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("governance.notPermitted"));
    }

    @Test
    void promote_byAnAdminReturns204() throws Exception {
        MemberId adminMemberId = MemberId.generate();
        MemberId participantMemberId = MemberId.generate();
        HouseholdId householdId = seedHouseholdWithAdminAndParticipant(adminMemberId, participantMemberId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/members/{memberId}/promote",
                        householdId.toString(),
                        participantMemberId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isNoContent());
    }

    @Test
    void demote_byAParticipantReturns403() throws Exception {
        MemberId adminMemberId = MemberId.generate();
        MemberId participantMemberId = MemberId.generate();
        HouseholdId householdId = seedHouseholdWithAdminAndParticipant(adminMemberId, participantMemberId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/members/{memberId}/demote",
                        householdId.toString(),
                        adminMemberId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(PARTICIPANT_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("governance.notPermitted"));
    }

    private static String commandBody() {
        return """
                {"commandId":"%s"}
                """.formatted(UUID.randomUUID());
    }
}
