package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.application.InviteEmailSideStore;
import de.sgart.collaboration.application.NormalizedEmail;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.readmodel.InviteReadModel;
import de.sgart.collaboration.domain.readmodel.InviteView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.FindHouseholdMemberByEmail;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * MockMvc slice over the real {@code InviteController}/handler/{@code ListPendingInvites} wiring,
 * with the durable adapters swapped for in-memory doubles — no live KurrentDB/PostgreSQL. Proves
 * AC1/AC3/AC4/AC6 end-to-end through REST: send ({@code 201}), duplicate-pending ({@code 409}),
 * already-a-member ({@code 409}), non-member ({@code 403}), invalid email ({@code 400}), list
 * pending invites ({@code 200}), and that no response body ever carries the raw email (AD-6).
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
    private FakeFindHouseholdMemberByEmail findHouseholdMemberByEmail;

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
        InMemoryInviteReadModel testInviteReadModel() {
            return new InMemoryInviteReadModel();
        }

        @Bean
        @Primary
        InviteEmailSideStore testInviteEmailSideStore() {
            return new InMemoryInviteEmailSideStore();
        }

        @Bean
        @Primary
        FakeFindHouseholdMemberByEmail testFindHouseholdMemberByEmail() {
            return new FakeFindHouseholdMemberByEmail();
        }
    }

    /** A read model whose pending-invite list a test can preset, so GET never touches PostgreSQL. */
    static final class InMemoryInviteReadModel implements InviteReadModel {
        List<InviteView> pendingInvites = List.of();

        @Override
        public List<InviteView> pendingInvitesOf(HouseholdId householdId) {
            return pendingInvites;
        }
    }

    static final class InMemoryInviteEmailSideStore implements InviteEmailSideStore {
        private final Map<InviteId, NormalizedEmail> emailsByInviteId = new HashMap<>();

        @Override
        public void store(InviteId inviteId, NormalizedEmail email) {
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

    /** Lets a test opt an email into "already a member" (AC3/E5) without a real Keycloak lookup. */
    static final class FakeFindHouseholdMemberByEmail implements FindHouseholdMemberByEmail {
        private final Map<String, MemberId> existingMembersByEmail = new HashMap<>();

        void existingMemberFor(String email, MemberId memberId) {
            existingMembersByEmail.put(email, memberId);
        }

        @Override
        public Optional<MemberId> forHousehold(String email, HouseholdId householdId) {
            return Optional.ofNullable(existingMembersByEmail.get(email));
        }
    }

    private HouseholdId seedHouseholdWithAdmin() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId adminMemberId = MemberId.generate();
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                household.uncommittedEvents(),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
        return householdId;
    }

    @Test
    void invite_returns201ForAMember() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/invites", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteRequestBody("anna@example.com", InviteId.generate().toString())))
                .andExpect(status().isCreated());
    }

    @Test
    void invite_returns409ForADuplicatePendingInvite() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        mockMvc.perform(post("/api/v1/households/{householdId}/invites", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteRequestBody("berta@example.com", InviteId.generate().toString())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/households/{householdId}/invites", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteRequestBody("berta@example.com", InviteId.generate().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invite.duplicatePending"));
    }

    @Test
    void invite_returns409ForAnAlreadyAHouseholdMemberEmail() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        findHouseholdMemberByEmail.existingMemberFor("carla@example.com", MemberId.generate());

        mockMvc.perform(post("/api/v1/households/{householdId}/invites", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteRequestBody("carla@example.com", InviteId.generate().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invite.alreadyAMember"));
    }

    @Test
    void invite_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/invites", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteRequestBody("dora@example.com", InviteId.generate().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void invite_rejectsAMalformedEmailWith400() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/invites", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteRequestBody("not-an-email", InviteId.generate().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invite.emailInvalid"));
    }

    @Test
    void invite_rejectsAnUnauthenticatedRequest() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/invites", householdId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteRequestBody("eva@example.com", InviteId.generate().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_returns200WithThePendingInvitesAndNeverAnEmailField() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        MemberId invitedBy = MemberId.generate();
        InviteId inviteId = InviteId.generate();
        inviteReadModel.pendingInvites = List.of(new InviteView(inviteId, Instant.now(), invitedBy, "PENDING"));

        mockMvc.perform(get("/api/v1/households/{householdId}/invites", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].inviteId").value(inviteId.toString()))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    void list_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(get("/api/v1/households/{householdId}/invites", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    private static String inviteRequestBody(String email, String inviteId) {
        return """
                {"inviteId":"%s","email":"%s","commandId":"%s"}
                """.formatted(inviteId, email, UUID.randomUUID());
    }
}
