package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.readmodel.ItemSuggestionReadModel;
import de.sgart.collaboration.domain.readmodel.ItemSuggestionView;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.Quantity;
import de.sgart.shared.StoreId;
import de.sgart.shared.Unit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc slice over the real {@code ItemSuggestionController}/{@code ListItemSuggestions} wiring,
 * with the durable adapters swapped for in-memory doubles ({@link InMemoryMemberMappingRepository}
 * and an in-memory {@link ItemSuggestionReadModel}) — no live PostgreSQL. Mirrors {@code
 * ItemControllerTest}'s GET test cases: 200 with the mapped list, 403 non-member, 400 malformed
 * householdId (Story 2.5, AC1/AC7).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ItemSuggestionControllerTest {

    private static final String MEMBER_SUB = "anna-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberMappingRepository mappingRepository;

    @Autowired
    private InMemoryItemSuggestionReadModel itemSuggestionReadModel;

    @TestConfiguration
    static class InMemoryAdaptersConfig {

        @Bean
        @Primary
        MemberMappingRepository testMemberMappingRepository() {
            return new InMemoryMemberMappingRepository();
        }

        @Bean
        @Primary
        InMemoryItemSuggestionReadModel testItemSuggestionReadModel() {
            return new InMemoryItemSuggestionReadModel();
        }
    }

    /** A read model whose suggestions a test presets per household, so GET never touches PostgreSQL. */
    static final class InMemoryItemSuggestionReadModel implements ItemSuggestionReadModel {
        private final Map<HouseholdId, List<ItemSuggestionView>> suggestionsByHousehold = new HashMap<>();

        void put(HouseholdId householdId, List<ItemSuggestionView> suggestions) {
            suggestionsByHousehold.put(householdId, suggestions);
        }

        @Override
        public List<ItemSuggestionView> suggestionsOf(HouseholdId householdId) {
            return suggestionsByHousehold.getOrDefault(householdId, List.of());
        }
    }

    private HouseholdId seedMembership() {
        HouseholdId householdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(MEMBER_SUB)));
        return householdId;
    }

    @Test
    void list_returns200WithTheMappedSuggestions() throws Exception {
        HouseholdId householdId = seedMembership();
        StoreId storeId = StoreId.generate();
        itemSuggestionReadModel.put(
                householdId,
                List.of(new ItemSuggestionView(
                        new ItemName("Milch"), new ItemNote("Bio"), Quantity.of(2, Unit.LITRE), storeId)));

        mockMvc.perform(get("/api/v1/households/{householdId}/item-suggestions", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Milch"))
                .andExpect(jsonPath("$[0].note").value("Bio"))
                .andExpect(jsonPath("$[0].amount").value("2"))
                .andExpect(jsonPath("$[0].unit").value("LITRE"))
                .andExpect(jsonPath("$[0].defaultStoreId").value(storeId.toString()));
    }

    @Test
    void list_rejectsANonMemberWith403() throws Exception {
        HouseholdId householdId = seedMembership();

        mockMvc.perform(get("/api/v1/households/{householdId}/item-suggestions", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("stranger-sub"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void list_rejectsAMalformedHouseholdIdWith400() throws Exception {
        mockMvc.perform(get("/api/v1/households/{householdId}/item-suggestions", "not-a-uuid")
                        .with(jwt().jwt(jwt -> jwt.subject(MEMBER_SUB))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("command.householdIdInvalid"));
    }
}
