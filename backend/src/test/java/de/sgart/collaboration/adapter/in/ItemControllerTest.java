package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.readmodel.ItemReadModel;
import de.sgart.collaboration.domain.readmodel.ItemView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.MemberId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import de.sgart.shared.Unit;
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
 * MockMvc slice over the real {@code ItemController}/handlers/{@code ListItems} wiring, with the
 * durable adapters swapped for in-memory doubles ({@link InMemoryEventStore}, {@link
 * InMemoryMemberMappingRepository}, and an in-memory {@link ItemReadModel}) — no live
 * KurrentDB/PostgreSQL. Proves Story 2.3 AC1–AC8 end-to-end through REST: add ({@code 201}), list
 * ({@code 200}), update ({@code 204}), remove ({@code 204}), and the 400/403/404/409 error surface.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ItemControllerTest {

    private static final String MEMBER_SUB = "anna-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private MemberMappingRepository mappingRepository;

    @Autowired
    private InMemoryItemReadModel itemReadModel;

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
        InMemoryItemReadModel testItemReadModel() {
            return new InMemoryItemReadModel();
        }
    }

    /** A read model whose items a test presets per list, so GET never touches PostgreSQL. */
    static final class InMemoryItemReadModel implements ItemReadModel {
        private final Map<ShoppingListId, List<ItemView>> itemsByList = new HashMap<>();

        void put(ShoppingListId listId, List<ItemView> items) {
            itemsByList.put(listId, items);
        }

        @Override
        public List<ItemView> itemsOf(HouseholdId householdId, ShoppingListId listId) {
            return itemsByList.getOrDefault(listId, List.of());
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

    private static String addRequestBody(String itemId, String name) {
        return "{\"itemId\":\"%s\",\"name\":\"%s\",\"amount\":\"1\",\"unit\":\"PIECE\",\"commandId\":\"%s\"}"
                .formatted(itemId, name, UUID.randomUUID());
    }

    @Test
    void add_returns201ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody(ItemId.generate().toString(), "Milch")))
                .andExpect(status().isCreated());
    }

    @Test
    void add_rejectsABlankNameWith400() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody(ItemId.generate().toString(), "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("item.nameRequired"));
    }

    @Test
    void add_rejectsANonPositiveAmountWith400() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"%s\",\"name\":\"Milch\",\"amount\":\"0\",\"unit\":\"PIECE\",\"commandId\":\"%s\"}"
                                .formatted(ItemId.generate(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("item.quantityInvalid"));
    }

    @Test
    void add_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody(ItemId.generate().toString(), "Milch")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void add_returns404ForAnUnknownList() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items",
                        householdId.toString(),
                        ShoppingListId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody(ItemId.generate().toString(), "Milch")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("list.notFound"));
    }

    @Test
    void add_returns409ForADuplicateNameAndNote() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody(ItemId.generate().toString(), "Milch")));

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody(ItemId.generate().toString(), "Milch")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("item.duplicate"));
    }

    @Test
    void list_returns200WithTheListsItems() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = ItemId.generate();
        itemReadModel.put(
                listId,
                List.of(new ItemView(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE))));

        mockMvc.perform(get(
                        "/api/v1/households/{householdId}/lists/{listId}/items",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemId").value(itemId.toString()))
                .andExpect(jsonPath("$[0].name").value("Milch"))
                .andExpect(jsonPath("$[0].note").value("Bio"))
                .andExpect(jsonPath("$[0].amount").value("1"))
                .andExpect(jsonPath("$[0].unit").value("PIECE"));
    }

    @Test
    void list_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(get(
                        "/api/v1/households/{householdId}/lists/{listId}/items",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void update_returns204ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = ItemId.generate();
        mockMvc.perform(post(
                "/api/v1/households/{householdId}/lists/{listId}/items", householdId.toString(), listId.toString())
                .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(addRequestBody(itemId.toString(), "Milch")));

        mockMvc.perform(patch(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}",
                        householdId.toString(),
                        listId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Milch\",\"note\":\"Bio\",\"amount\":\"2\",\"unit\":\"PIECE\",\"commandId\":\"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isNoContent());
    }

    @Test
    void update_returns404ForAnUnknownItem() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(patch(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}",
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Milch\",\"amount\":\"1\",\"unit\":\"PIECE\",\"commandId\":\"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("item.notFound"));
    }

    @Test
    void remove_returns204ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = ItemId.generate();
        mockMvc.perform(post(
                "/api/v1/households/{householdId}/lists/{listId}/items", householdId.toString(), listId.toString())
                .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(addRequestBody(itemId.toString(), "Milch")));

        mockMvc.perform(delete(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}",
                        householdId.toString(),
                        listId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNoContent());
    }

    @Test
    void remove_isIdempotentForAnUnknownItem() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(delete(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}",
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNoContent());
    }

    @Test
    void remove_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(delete(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}",
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }
}
