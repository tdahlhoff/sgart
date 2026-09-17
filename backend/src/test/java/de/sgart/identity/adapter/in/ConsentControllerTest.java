package de.sgart.identity.adapter.in;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.identity.adapter.out.InMemoryAccountConsentRepository;
import de.sgart.identity.domain.AccountConsentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc slice over the real {@code ConsentController}/application wiring, with the durable
 * repository swapped for an in-memory double — no live PostgreSQL (mirrors {@code
 * AccountControllerTest}). Proves Story 7.4 AC1/AC2/AC6 end-to-end through REST.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountConsentRepository accountConsentRepository;

    @TestConfiguration
    static class InMemoryAdaptersConfig {

        @Bean
        @Primary
        AccountConsentRepository testAccountConsentRepository() {
            return new InMemoryAccountConsentRepository();
        }
    }

    @BeforeEach
    void clearSharedRepository() {
        ((InMemoryAccountConsentRepository) accountConsentRepository).clear();
    }

    @Test
    void accept_returns204AndRecordsTheConsent() throws Exception {
        mockMvc.perform(post("/api/v1/consent").with(jwt().jwt(jwt -> jwt.subject("anna-sub"))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/consent").with(jwt().jwt(jwt -> jwt.subject("anna-sub"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.acceptedVersion").value("2026-beta-1"))
                .andExpect(jsonPath("$.currentVersion").value("2026-beta-1"));
    }

    @Test
    void status_withNoRecordedConsent_returnsNotAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/consent").with(jwt().jwt(jwt -> jwt.subject("never-consented-sub"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.acceptedVersion").value(nullValue()))
                .andExpect(jsonPath("$.currentVersion").value("2026-beta-1"));
    }

    @Test
    void consentEndpoints_requireAuth_andAddNoUnauthenticatedSurface() throws Exception {
        mockMvc.perform(get("/api/v1/consent")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/consent")).andExpect(status().isUnauthorized());
    }
}
