package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ItemStatus;
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
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import de.sgart.shared.Unit;
import de.sgart.shared.support.InMemoryEventStore;
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
 * MockMvc slice over the real {@code ItemController}/handlers/{@code ListItems} wiring, with the
 * durable adapters swapped for in-memory doubles ({@link InMemoryEventStore}, {@link
 * InMemoryMemberMappingRepository}, and an in-memory {@link ItemReadModel}) — no live
 * KurrentDB/PostgreSQL. Proves Story 2.3 AC1–AC8 end-to-end through REST: add ({@code 201}), list
 * ({@code 200}), update ({@code 204}), remove ({@code 204}), and the 400/403/404/409 error surface;
 * Story 2.4 adds move ({@code 204}) and its 400/403/404 surface. The move's {@code 409
 * list.moveTargetNotOpen} branch is coded ({@code MoveItemHandler}) but, like the sibling DONE
 * branches elsewhere in this suite (see {@code ShoppingListItemsTest}), only reachable end-to-end
 * once Epic 3 introduces a status-changing transition — no synthetic Epic-3 event exists to drive a
 * list into a non-Open status, so it is not exercised here; see {@code deferred-work.md}.
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

        /** The projector's lookup (Story 2.5, Cl. 5) — never reached through a controller GET. */
        @Override
        public Optional<HouseholdId> householdIdOf(ItemId itemId) {
            return Optional.empty();
        }

        /** The projector's write (Story 2.6) — never reached through this slice's command endpoints. */
        @Override
        public void assignStore(ItemId itemId, StoreId storeId) {
            // no-op — this test double is preset via put(...), never mutated by the projector.
        }

        /** The projector's lookup (Story 2.6, Cl. 6) — never reached through a controller GET. */
        @Override
        public Optional<ItemName> nameOf(ItemId itemId) {
            return Optional.empty();
        }

        /** The projector's status write (Story 3.3) — never reached through this slice's command endpoints. */
        @Override
        public void setStatus(ItemId itemId, ItemStatus status) {
            // no-op — this test double is preset via put(...), never mutated by the projector.
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

    private ItemId seedItemIn(ShoppingListId listId, String name) {
        ItemId itemId = ItemId.generate();
        ShoppingList list = ShoppingList.rehydrate(StreamId.forList(listId), eventStore.readStream(StreamId.forList(listId)));
        AggregateVersion loadedVersion = list.version();
        list.addItem(itemId, new ItemName(name), null, Quantity.of(1, Unit.PIECE), CommandId.generate());
        eventStore.append(loadedVersion, list.uncommittedEvents(), CommandId.generate());
        return itemId;
    }

    private void startTripOn(ShoppingListId listId, StoreId storeId) {
        ShoppingList list = ShoppingList.rehydrate(StreamId.forList(listId), eventStore.readStream(StreamId.forList(listId)));
        AggregateVersion loadedVersion = list.version();
        list.startTrip(TripId.generate(), List.of(storeId), CommandId.generate());
        eventStore.append(loadedVersion, list.uncommittedEvents(), CommandId.generate());
    }

    private static String rerouteRequestBody(String storeId) {
        return "{\"storeId\":\"%s\",\"commandId\":\"%s\"}".formatted(storeId, UUID.randomUUID());
    }

    @Test
    void reroute_returns200ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = seedItemIn(listId, "Milch");
        startTripOn(listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/reroute",
                        householdId.toString(),
                        listId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rerouteRequestBody(StoreId.generate().toString())))
                .andExpect(status().isOk());
    }

    @Test
    void reroute_rejectsAMalformedStoreIdWith400() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = seedItemIn(listId, "Milch");
        startTripOn(listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/reroute",
                        householdId.toString(),
                        listId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rerouteRequestBody("not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.storeIdInvalid"));
    }

    @Test
    void reroute_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = seedItemIn(listId, "Milch");
        startTripOn(listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/reroute",
                        householdId.toString(),
                        listId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rerouteRequestBody(StoreId.generate().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void reroute_returns404ForAnUnknownList() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/reroute",
                        householdId.toString(),
                        ShoppingListId.generate().toString(),
                        ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rerouteRequestBody(StoreId.generate().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("list.notFound"));
    }

    @Test
    void reroute_returns404ForAnUnknownItem() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        startTripOn(listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/reroute",
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rerouteRequestBody(StoreId.generate().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("item.notFound"));
    }

    @Test
    void reroute_returns409WhenTheListIsNotInTrip() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = seedItemIn(listId, "Milch");
        // No startTripOn(...) — the list is still Open.

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/reroute",
                        householdId.toString(),
                        listId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rerouteRequestBody(StoreId.generate().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("item.notDuringTrip"));
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
                List.of(new ItemView(itemId, new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(1, Unit.PIECE), null, ItemStatus.OPEN)));

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
    void move_returns204ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId sourceListId = seedListIn(householdId);
        ShoppingListId targetListId = seedListIn(householdId);
        ItemId itemId = ItemId.generate();
        mockMvc.perform(post(
                "/api/v1/households/{householdId}/lists/{listId}/items", householdId.toString(), sourceListId.toString())
                .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(addRequestBody(itemId.toString(), "Milch")));

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/move",
                        householdId.toString(),
                        sourceListId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetListId\":\"%s\",\"commandId\":\"%s\"}"
                                .formatted(targetListId, UUID.randomUUID())))
                .andExpect(status().isNoContent());
    }

    @Test
    void move_returns400WhenTargetEqualsSource() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = ItemId.generate();
        mockMvc.perform(post(
                "/api/v1/households/{householdId}/lists/{listId}/items", householdId.toString(), listId.toString())
                .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(addRequestBody(itemId.toString(), "Milch")));

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/move",
                        householdId.toString(),
                        listId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetListId\":\"%s\",\"commandId\":\"%s\"}"
                                .formatted(listId, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("list.moveTargetSameAsSource"));
    }

    @Test
    void move_returns404ForAnUnknownSource() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId targetListId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/move",
                        householdId.toString(),
                        ShoppingListId.generate().toString(),
                        ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetListId\":\"%s\",\"commandId\":\"%s\"}"
                                .formatted(targetListId, UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("list.notFound"));
    }

    @Test
    void move_returns404ForAnUnknownTarget() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId sourceListId = seedListIn(householdId);
        ItemId itemId = ItemId.generate();
        mockMvc.perform(post(
                "/api/v1/households/{householdId}/lists/{listId}/items", householdId.toString(), sourceListId.toString())
                .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(addRequestBody(itemId.toString(), "Milch")));

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/move",
                        householdId.toString(),
                        sourceListId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetListId\":\"%s\",\"commandId\":\"%s\"}"
                                .formatted(ShoppingListId.generate(), UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("list.notFound"));
    }

    @Test
    void move_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId sourceListId = seedListIn(householdId);
        ShoppingListId targetListId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/move",
                        householdId.toString(),
                        sourceListId.toString(),
                        ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetListId\":\"%s\",\"commandId\":\"%s\"}"
                                .formatted(targetListId, UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void assignStore_returns204ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = ItemId.generate();
        mockMvc.perform(post(
                "/api/v1/households/{householdId}/lists/{listId}/items", householdId.toString(), listId.toString())
                .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(addRequestBody(itemId.toString(), "Milch")));

        mockMvc.perform(put(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/store",
                        householdId.toString(),
                        listId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"%s\",\"commandId\":\"%s\"}"
                                .formatted(StoreId.generate(), UUID.randomUUID())))
                .andExpect(status().isNoContent());
    }

    @Test
    void assignStore_rejectsAMalformedStoreIdWith400() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = ItemId.generate();
        mockMvc.perform(post(
                "/api/v1/households/{householdId}/lists/{listId}/items", householdId.toString(), listId.toString())
                .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(addRequestBody(itemId.toString(), "Milch")));

        mockMvc.perform(put(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/store",
                        householdId.toString(),
                        listId.toString(),
                        itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"not-a-uuid\",\"commandId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.storeIdInvalid"));
    }

    @Test
    void assignStore_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(put(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/store",
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"%s\",\"commandId\":\"%s\"}"
                                .formatted(StoreId.generate(), UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void assignStore_returns404ForAnUnknownList() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(put(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/store",
                        householdId.toString(),
                        ShoppingListId.generate().toString(),
                        ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"%s\",\"commandId\":\"%s\"}"
                                .formatted(StoreId.generate(), UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("list.notFound"));
    }

    @Test
    void assignStore_returns404ForAnUnknownItem() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(put(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/store",
                        householdId.toString(),
                        listId.toString(),
                        ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"%s\",\"commandId\":\"%s\"}"
                                .formatted(StoreId.generate(), UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("item.notFound"));
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

    // --- Story 3.3: the four in-trip status endpoints + their error-advice contract ---

    private static String commandBody() {
        return "{\"commandId\":\"%s\"}".formatted(UUID.randomUUID());
    }

    private static String postponeToListBody(String targetListId) {
        return "{\"targetListId\":\"%s\",\"commandId\":\"%s\"}".formatted(targetListId, UUID.randomUUID());
    }

    @Test
    void checkOff_returns200ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = seedItemIn(listId, "Milch");
        startTripOn(listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/check-off",
                        householdId.toString(), listId.toString(), itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isOk());
    }

    @Test
    void checkOff_returns409WhenTheListIsNotInTrip() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = seedItemIn(listId, "Milch");
        // No startTripOn(...) — the list is still Open.

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/check-off",
                        householdId.toString(), listId.toString(), itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("item.notDuringTrip"));
    }

    @Test
    void uncheck_returns200ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = seedItemIn(listId, "Milch");
        startTripOn(listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/uncheck",
                        householdId.toString(), listId.toString(), itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isOk());
    }

    @Test
    void uncheck_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/uncheck",
                        householdId.toString(), listId.toString(), ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void postpone_returns200ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = seedItemIn(listId, "Milch");
        startTripOn(listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/postpone",
                        householdId.toString(), listId.toString(), itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isOk());
    }

    @Test
    void postpone_returns404ForAnUnknownItem() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        startTripOn(listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/postpone",
                        householdId.toString(), listId.toString(), ItemId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("item.notFound"));
    }

    @Test
    void postponeToList_returns200ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId sourceListId = seedListIn(householdId);
        ShoppingListId targetListId = seedListIn(householdId);
        ItemId itemId = seedItemIn(sourceListId, "Milch");
        startTripOn(sourceListId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/postpone-to-list",
                        householdId.toString(), sourceListId.toString(), itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postponeToListBody(targetListId.toString())))
                .andExpect(status().isOk());
    }

    @Test
    void postponeToList_returns400WhenTargetEqualsSource() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        ItemId itemId = seedItemIn(listId, "Milch");
        startTripOn(listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/postpone-to-list",
                        householdId.toString(), listId.toString(), itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postponeToListBody(listId.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("list.moveTargetSameAsSource"));
    }

    @Test
    void postponeToList_returns409WhenTargetIsNotOpen() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId sourceListId = seedListIn(householdId);
        ShoppingListId targetListId = seedListIn(householdId);
        ItemId itemId = seedItemIn(sourceListId, "Milch");
        startTripOn(sourceListId, StoreId.generate());
        startTripOn(targetListId, StoreId.generate()); // target is now IN_TRIP, not Open

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/postpone-to-list",
                        householdId.toString(), sourceListId.toString(), itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postponeToListBody(targetListId.toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("list.moveTargetNotOpen"));
    }

    @Test
    void postponeToList_returns404ForAnUnknownTarget() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId sourceListId = seedListIn(householdId);
        ItemId itemId = seedItemIn(sourceListId, "Milch");
        startTripOn(sourceListId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/items/{itemId}/postpone-to-list",
                        householdId.toString(), sourceListId.toString(), itemId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postponeToListBody(ShoppingListId.generate().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("list.notFound"));
    }
}
