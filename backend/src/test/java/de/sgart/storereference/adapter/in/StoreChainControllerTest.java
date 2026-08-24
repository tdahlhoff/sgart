package de.sgart.storereference.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.shared.StoreChainId;
import de.sgart.storereference.domain.StoreChainReference;
import de.sgart.storereference.domain.StoreChainReferenceReadModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc slice over the real {@code StoreChainController}/{@code ListStoreChains} wiring, with the
 * durable read model swapped for an in-memory double — no live PostgreSQL. Proves AC2's reference
 * endpoint returns the seeded list and rejects an unauthenticated request.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StoreChainControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class InMemoryReferenceConfig {

        @Bean
        @Primary
        StoreChainReferenceReadModel testStoreChainReferenceReadModel() {
            return () -> List.of(
                    new StoreChainReference(StoreChainId.generate(), "Edeka"),
                    new StoreChainReference(StoreChainId.generate(), "Rewe"));
        }
    }

    @Test
    void list_returns200WithTheSeededChains() throws Exception {
        mockMvc.perform(get("/api/v1/store-chains").with(jwt().jwt(jwt -> jwt.subject("anna-sub"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("Edeka"))
                .andExpect(jsonPath("$[0].chainId").isNotEmpty())
                .andExpect(jsonPath("$[1].name").value("Rewe"));
    }

    @Test
    void list_rejectsAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/store-chains")).andExpect(status().isUnauthorized());
    }
}
