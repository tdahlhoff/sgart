package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * MockMvc slice over the real {@code ShoppingListController}/handlers/{@code ListOpenLists} wiring,
 * with the durable adapters swapped for in-memory doubles ({@link InMemoryEventStore}, {@link
 * InMemoryMemberMappingRepository}, and an in-memory {@link ShoppingListReadModel}) — no live
 * KurrentDB/PostgreSQL. Proves Story 2.1 AC1/AC2/AC3 and Story 2.2 AC1/AC2 end-to-end through REST:
 * create ({@code 201}), rename ({@code 204}), list ({@code 200}, {@code ?filter=open|done}), the
 * 400/401/403/404 error surface, and that the caller identity comes only from the JWT {@code sub}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShoppingListControllerTest {

    private static final String MEMBER_SUB = "anna-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private MemberMappingRepository mappingRepository;

    @Autowired
    private InMemoryShoppingListReadModel shoppingListReadModel;

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
        InMemoryShoppingListReadModel testShoppingListReadModel() {
            return new InMemoryShoppingListReadModel();
        }
    }

    /**
     * A read model whose lists a test presets per household, so GET never touches PostgreSQL and the
     * per-household scoping is actually exercised (the double honors its {@code householdId}
     * argument, not just returns a single preset list).
     */
    static final class InMemoryShoppingListReadModel implements ShoppingListReadModel {
        private final Map<HouseholdId, List<ShoppingListView>> listsByHousehold = new HashMap<>();

        void put(HouseholdId householdId, List<ShoppingListView> lists) {
            listsByHousehold.put(householdId, lists);
        }

        @Override
        public List<ShoppingListView> listsOf(HouseholdId householdId) {
            return listsByHousehold.getOrDefault(householdId, List.of());
        }
    }

    private HouseholdId seedMembership() {
        HouseholdId householdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
        return householdId;
    }

    private ShoppingListId seedListIn(HouseholdId householdId) {
        ShoppingListId listId = ShoppingListId.generate();
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forList(listId)), list.uncommittedEvents(), CommandId.generate());
        return listId;
    }

    @Test
    void create_returns201ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(post("/api/v1/households/{householdId}/lists", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody(ShoppingListId.generate().toString(), "Wocheneinkauf")))
                .andExpect(status().isCreated());
    }

    @Test
    void create_returns201ForAnUnnamedList() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(post("/api/v1/households/{householdId}/lists", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listId\":\"%s\",\"commandId\":\"%s\"}"
                                .formatted(ShoppingListId.generate(), UUID.randomUUID())))
                .andExpect(status().isCreated());
    }

    @Test
    void create_rejectsABlankCommandIdWith400NotA500() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(post("/api/v1/households/{householdId}/lists", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listId\":\"%s\",\"name\":\"Wocheneinkauf\"}"
                                .formatted(ShoppingListId.generate())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.commandIdRequired"));
    }

    @Test
    void create_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(post("/api/v1/households/{householdId}/lists", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody(ShoppingListId.generate().toString(), "Wocheneinkauf")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void create_rejectsAnUnauthenticatedRequest() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(post("/api/v1/households/{householdId}/lists", householdId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody(ShoppingListId.generate().toString(), "Wocheneinkauf")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_returns200WithTheOpenLists() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = ShoppingListId.generate();
        shoppingListReadModel.put(
                householdId, List.of(new ShoppingListView(listId, new ShoppingListName("Getränke"), ListStatus.OPEN)));

        mockMvc.perform(get("/api/v1/households/{householdId}/lists", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].listId").value(listId.toString()))
                .andExpect(jsonPath("$[0].name").value("Getränke"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void list_returnsOnlyTheCallersHouseholdLists() throws Exception {
        HouseholdId householdId = seedMembership();
        HouseholdId otherHousehold = HouseholdId.generate();
        shoppingListReadModel.put(
                otherHousehold,
                List.of(new ShoppingListView(ShoppingListId.generate(), new ShoppingListName("Fremd"), ListStatus.OPEN)));

        mockMvc.perform(get("/api/v1/households/{householdId}/lists", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void list_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(get("/api/v1/households/{householdId}/lists", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void getWithoutFilterStillReturnsOpenLists() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = ShoppingListId.generate();
        shoppingListReadModel.put(
                householdId, List.of(new ShoppingListView(listId, new ShoppingListName("Getränke"), ListStatus.OPEN)));

        mockMvc.perform(get("/api/v1/households/{householdId}/lists", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void getWithOpenFilterReturnsOpenLists() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = ShoppingListId.generate();
        shoppingListReadModel.put(
                householdId, List.of(new ShoppingListView(listId, new ShoppingListName("Getränke"), ListStatus.OPEN)));

        mockMvc.perform(get("/api/v1/households/{householdId}/lists", householdId.toString())
                        .param("filter", "open")
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void getWithDoneFilterReturnsTheCallersDoneLists() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId doneId = ShoppingListId.generate();
        shoppingListReadModel.put(
                householdId,
                List.of(new ShoppingListView(doneId, new ShoppingListName("Alte Liste"), ListStatus.DONE)));

        mockMvc.perform(get("/api/v1/households/{householdId}/lists", householdId.toString())
                        .param("filter", "done")
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].listId").value(doneId.toString()))
                .andExpect(jsonPath("$[0].name").value("Alte Liste"))
                .andExpect(jsonPath("$[0].status").value("DONE"));
    }

    @Test
    void anotherHouseholdsDoneListsAreExcludedFromTheArchive() throws Exception {
        HouseholdId householdId = seedMembership();
        HouseholdId otherHousehold = HouseholdId.generate();
        shoppingListReadModel.put(
                otherHousehold,
                List.of(new ShoppingListView(ShoppingListId.generate(), new ShoppingListName("Fremd"), ListStatus.DONE)));

        mockMvc.perform(get("/api/v1/households/{householdId}/lists", householdId.toString())
                        .param("filter", "done")
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anUnknownFilterIsRejectedWithFourHundred() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(get("/api/v1/households/{householdId}/lists", householdId.toString())
                        .param("filter", "bogus")
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.listFilterInvalid"));
    }

    @Test
    void list_withDoneFilterRejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(get("/api/v1/households/{householdId}/lists", householdId.toString())
                        .param("filter", "done")
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void rename_returns204ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(patch("/api/v1/households/{householdId}/lists/{listId}", householdId.toString(), listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Getränke\",\"commandId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNoContent());
    }

    @Test
    void rename_rejectsABlankNameWith400() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(patch("/api/v1/households/{householdId}/lists/{listId}", householdId.toString(), listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"commandId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("list.nameRequired"));
    }

    @Test
    void rename_returns404ForAnUnknownList() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(patch(
                        "/api/v1/households/{householdId}/lists/{listId}",
                        householdId.toString(),
                        ShoppingListId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Getränke\",\"commandId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("list.notFound"));
    }

    @Test
    void rename_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(patch("/api/v1/households/{householdId}/lists/{listId}", householdId.toString(), listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Getränke\",\"commandId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    private static String createRequestBody(String listId, String name) {
        return """
                {"listId":"%s","name":"%s","commandId":"%s"}
                """.formatted(listId, name, UUID.randomUUID());
    }
}
