package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.domain.ShoppingList;
import de.sgart.collaboration.domain.ShoppingListName;
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
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.Arrays;
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
