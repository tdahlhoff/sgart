package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemStatus;
import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.ShoppingTrip;
import de.sgart.collaboration.domain.readmodel.ItemReadModel;
import de.sgart.collaboration.domain.readmodel.ItemView;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListView;
import de.sgart.collaboration.domain.readmodel.TripStoreReadModel;
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
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.Arrays;
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
 * MockMvc slice over the real {@code TripController}/{@code StartTripHandler} wiring, with the
 * durable adapters swapped for in-memory doubles ({@link InMemoryEventStore}, {@link
 * InMemoryMemberMappingRepository}) — no live KurrentDB/PostgreSQL. Proves Story 3.1 AC1/AC3/AC7
 * end-to-end through REST: start ({@code 201}), and the 400/403/404/409 error surface — this
 * doubles as the Action-2 error-advice contract coverage for the new endpoint (retro Action 2).
 */
@SpringBootTest
@AutoConfigureMockMvc
class TripControllerTest {

    private static final String MEMBER_SUB = "anna-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private MemberMappingRepository mappingRepository;

    @Autowired
    private InMemoryShoppingListReadModel shoppingListReadModel;

    @Autowired
    private InMemoryTripStoreReadModel tripStoreReadModel;

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
        InMemoryShoppingListReadModel testShoppingListReadModel() {
            return new InMemoryShoppingListReadModel();
        }

        @Bean
        @Primary
        InMemoryTripStoreReadModel testTripStoreReadModel() {
            return new InMemoryTripStoreReadModel();
        }

        @Bean
        @Primary
        InMemoryItemReadModel testItemReadModel() {
            return new InMemoryItemReadModel();
        }
    }

    /** A read model whose lists a test presets, so {@code GET .../trips/active} never touches PostgreSQL. */
    static final class InMemoryShoppingListReadModel implements ShoppingListReadModel {
        private final Map<HouseholdId, List<ShoppingListView>> listsByHousehold = new HashMap<>();

        void put(HouseholdId householdId, ShoppingListView view) {
            listsByHousehold.computeIfAbsent(householdId, ignored -> new java.util.ArrayList<>()).add(view);
        }

        @Override
        public List<ShoppingListView> listsOf(HouseholdId householdId) {
            return listsByHousehold.getOrDefault(householdId, List.of());
        }
    }

    /** A read model whose stores a test presets, so the trip view never touches PostgreSQL. */
    static final class InMemoryTripStoreReadModel implements TripStoreReadModel {
        private final Map<TripId, List<StoreId>> storesByTrip = new HashMap<>();

        void put(TripId tripId, List<StoreId> stores) {
            storesByTrip.put(tripId, stores);
        }

        @Override
        public void addStore(TripId tripId, StoreId storeId) {
            // no-op — this test double is preset via put(...), never mutated by the projector.
        }

        @Override
        public List<StoreId> storesOf(TripId tripId) {
            return storesByTrip.getOrDefault(tripId, List.of());
        }
    }

    /** A read model whose items a test presets per list, so the trip view never touches PostgreSQL. */
    static final class InMemoryItemReadModel implements ItemReadModel {
        private final Map<ShoppingListId, List<ItemView>> itemsByList = new HashMap<>();

        void put(ShoppingListId listId, List<ItemView> items) {
            itemsByList.put(listId, items);
        }

        @Override
        public List<ItemView> itemsOf(HouseholdId householdId, ShoppingListId listId) {
            return itemsByList.getOrDefault(listId, List.of());
        }

        @Override
        public Optional<HouseholdId> householdIdOf(ItemId itemId) {
            return Optional.empty();
        }

        @Override
        public void assignStore(ItemId itemId, StoreId storeId) {
            // no-op — this test double is preset via put(...), never mutated by the projector.
        }

        @Override
        public Optional<ItemName> nameOf(ItemId itemId) {
            return Optional.empty();
        }

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

    private TripId seedActiveTripFor(HouseholdId householdId, ShoppingListId listId, StoreId... storeIds) {
        TripId tripId = TripId.generate();
        ShoppingTrip trip =
                ShoppingTrip.start(tripId, householdId, listId, Arrays.asList(storeIds), CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forTrip(tripId)), trip.uncommittedEvents(), CommandId.generate());
        shoppingListReadModel.put(
                householdId,
                new ShoppingListView(listId, new ShoppingListName("Wocheneinkauf"), ListStatus.IN_TRIP, 0, tripId));
        tripStoreReadModel.put(tripId, Arrays.asList(storeIds));
        return tripId;
    }

    private static String addStoreRequestBody(String storeId) {
        return "{\"storeId\":\"%s\",\"commandId\":\"%s\"}".formatted(storeId, UUID.randomUUID());
    }

    @Test
    void activeTrip_returns200WithTheGroupedPayload() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        StoreId storeId = StoreId.generate();
        TripId tripId = seedActiveTripFor(householdId, listId, storeId);

        mockMvc.perform(get(
                        "/api/v1/households/{householdId}/lists/{listId}/trips/active",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(tripId.toString()))
                .andExpect(jsonPath("$.storeIds[0]").value(storeId.toString()));
    }

    @Test
    void activeTrip_returns404WhenTheListHasNoActiveTrip() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        shoppingListReadModel.put(
                householdId, new ShoppingListView(listId, new ShoppingListName("Wocheneinkauf"), ListStatus.OPEN, 0, null));

        mockMvc.perform(get(
                        "/api/v1/households/{householdId}/lists/{listId}/trips/active",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("trip.notFound"));
    }

    @Test
    void activeTrip_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        seedActiveTripFor(householdId, listId, StoreId.generate());

        mockMvc.perform(get(
                        "/api/v1/households/{householdId}/lists/{listId}/trips/active",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void addStore_returns201ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        TripId tripId = seedActiveTripFor(householdId, listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips/{tripId}/stores",
                        householdId.toString(),
                        listId.toString(),
                        tripId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addStoreRequestBody(StoreId.generate().toString())))
                .andExpect(status().isCreated());
    }

    @Test
    void addStore_rejectsAMalformedStoreIdWith400() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        TripId tripId = seedActiveTripFor(householdId, listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips/{tripId}/stores",
                        householdId.toString(),
                        listId.toString(),
                        tripId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addStoreRequestBody("not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.storeIdInvalid"));
    }

    @Test
    void addStore_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        TripId tripId = seedActiveTripFor(householdId, listId, StoreId.generate());

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips/{tripId}/stores",
                        householdId.toString(),
                        listId.toString(),
                        tripId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addStoreRequestBody(StoreId.generate().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void addStore_returns404ForAnUnknownTrip() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips/{tripId}/stores",
                        householdId.toString(),
                        listId.toString(),
                        TripId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addStoreRequestBody(StoreId.generate().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("trip.notFound"));
    }

    @Test
    void addStore_returns404ForATripInAnotherHousehold() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        TripId tripId = seedActiveTripFor(householdId, listId, StoreId.generate());
        HouseholdId otherHouseholdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(otherHouseholdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips/{tripId}/stores",
                        otherHouseholdId.toString(),
                        listId.toString(),
                        tripId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addStoreRequestBody(StoreId.generate().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("trip.notFound"));
    }

    private static String startRequestBody(String tripId, String... storeIds) {
        String storeIdsJson = String.join(",", Arrays.stream(storeIds).map("\"%s\""::formatted).toList());
        return "{\"tripId\":\"%s\",\"storeIds\":[%s],\"commandId\":\"%s\"}"
                .formatted(tripId, storeIdsJson, UUID.randomUUID());
    }

    @Test
    void start_returns201ForAMember() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequestBody(TripId.generate().toString(), StoreId.generate().toString())))
                .andExpect(status().isCreated());
    }

    @Test
    void start_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequestBody(TripId.generate().toString(), StoreId.generate().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void start_rejectsAMalformedTripIdWith400() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequestBody("not-a-uuid", StoreId.generate().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.tripIdInvalid"));
    }

    @Test
    void start_rejectsAnEmptyStoreSelectionWith400() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequestBody(TripId.generate().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("trip.storeSelectionRequired"));
    }

    @Test
    void start_returns404ForAnUnknownList() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips",
                        householdId.toString(),
                        ShoppingListId.generate().toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequestBody(TripId.generate().toString(), StoreId.generate().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("list.notFound"));
    }

    @Test
    void start_returns409ForAnAlreadyInTripList() throws Exception {
        HouseholdId householdId = seedMembership();
        ShoppingListId listId = seedListIn(householdId);
        mockMvc.perform(post(
                "/api/v1/households/{householdId}/lists/{listId}/trips", householdId.toString(), listId.toString())
                .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(startRequestBody(TripId.generate().toString(), StoreId.generate().toString())));

        mockMvc.perform(post(
                        "/api/v1/households/{householdId}/lists/{listId}/trips",
                        householdId.toString(),
                        listId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequestBody(TripId.generate().toString(), StoreId.generate().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("trip.notStartable"));
    }
}
