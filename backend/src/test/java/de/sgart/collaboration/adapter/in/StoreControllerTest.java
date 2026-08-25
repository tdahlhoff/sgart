package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.application.query.ListStores;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.collaboration.domain.readmodel.StoreReadModel;
import de.sgart.collaboration.domain.readmodel.StoreView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
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
 * MockMvc slice over the real {@code StoreController}/handlers/{@code ListStores} wiring, with the
 * durable adapters swapped for in-memory doubles ({@link InMemoryEventStore}, {@link
 * InMemoryMemberMappingRepository}, and an in-memory {@link StoreReadModel}) — no live
 * KurrentDB/PostgreSQL. Proves AC1/AC3 end-to-end through REST: add ({@code 201}), duplicate
 * ({@code 409}), archive ({@code 204}), list ({@code 200}), the 400/401/403 error surface, and that
 * the caller identity comes only from the JWT {@code sub}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StoreControllerTest {

    private static final String ADMIN_SUB = "anna-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private MemberMappingRepository mappingRepository;

    @Autowired
    private InMemoryStoreReadModel storeReadModel;

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
        InMemoryStoreReadModel testStoreReadModel() {
            return new InMemoryStoreReadModel();
        }
    }

    /** A read model whose active-store list a test can preset, so GET never touches PostgreSQL. */
    static final class InMemoryStoreReadModel implements StoreReadModel {
        List<StoreView> activeStores = List.of();

        @Override
        public List<StoreView> activeStoresOf(HouseholdId householdId) {
            return activeStores;
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
    void add_returns201ForAMember() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/stores", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody("Edeka Schiedemann", StoreId.generate().toString())))
                .andExpect(status().isCreated());
    }

    @Test
    void add_returns409ForADuplicateActiveName() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        mockMvc.perform(post("/api/v1/households/{householdId}/stores", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody("Edeka", StoreId.generate().toString())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/households/{householdId}/stores", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody("edeka", StoreId.generate().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("store.duplicateName"));
    }

    @Test
    void add_rejectsABlankNameWith400() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/stores", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody("   ", StoreId.generate().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("store.nameRequired"));
    }

    @Test
    void add_rejectsAMissingCommandIdWith400NotA500() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/stores", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"%s\",\"name\":\"Edeka\"}".formatted(StoreId.generate())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.commandIdRequired"));
    }

    @Test
    void add_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/stores", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody("Edeka", StoreId.generate().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void add_rejectsAnUnauthenticatedRequest() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(post("/api/v1/households/{householdId}/stores", householdId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody("Edeka", StoreId.generate().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void archive_returns204ForAMember() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        String storeId = StoreId.generate().toString();
        mockMvc.perform(post("/api/v1/households/{householdId}/stores", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody("Edeka", storeId)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/households/{householdId}/stores/{storeId}", householdId.toString(), storeId)
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNoContent());
    }

    @Test
    void list_returns200WithTheActiveStores() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();
        storeReadModel.activeStores = List.of(
                new StoreView(StoreId.generate(), new StoreName("Edeka"), StoreChainId.generate()),
                new StoreView(StoreId.generate(), new StoreName("Wochenmarkt"), null));

        mockMvc.perform(get("/api/v1/households/{householdId}/stores", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("Edeka"))
                .andExpect(jsonPath("$[0].chainId").isNotEmpty())
                .andExpect(jsonPath("$[1].name").value("Wochenmarkt"));
    }

    @Test
    void list_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedHouseholdWithAdmin();

        mockMvc.perform(get("/api/v1/households/{householdId}/stores", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    private static String addRequestBody(String name, String storeId) {
        return """
                {"storeId":"%s","name":"%s","commandId":"%s"}
                """.formatted(storeId, name, UUID.randomUUID());
    }
}
