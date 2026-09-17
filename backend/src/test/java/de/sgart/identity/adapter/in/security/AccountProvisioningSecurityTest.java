package de.sgart.identity.adapter.in.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.identity.adapter.out.InMemoryProvisionedAccountRepository;
import de.sgart.identity.domain.ProvisionedAccountRepository;
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
 * Guards the single deliberate unauthenticated write surface (Story 7.1, D-E, AC4): {@code POST
 * /api/v1/accounts} must be reachable with no JWT, while every other {@code /api/v1/**} endpoint
 * must still reject an unauthenticated request with {@code 401} — proving the new permit matcher
 * is scoped to exactly that method+path and did not shadow the blanket authenticated rule below it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountProvisioningSecurityTest {

    private static final String VALID_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class InMemoryAdaptersConfig {

        // No live PostgreSQL/Keycloak in this slice — only the security filter chain is under
        // test, mirroring AccountControllerTest's in-memory swap.
        @Bean
        @Primary
        ProvisionedAccountRepository testProvisionedAccountRepository() {
            return new InMemoryProvisionedAccountRepository();
        }
    }

    @Test
    void provisionEndpoint_isReachableUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKey\":\"" + VALID_PUBLIC_KEY + "\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void otherApiV1Endpoint_withoutJwt_is401() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void aGetOnTheAccountsPath_withoutJwt_is401() throws Exception {
        // The permit matcher is scoped to POST only (D-E) — GET on the same path must still 401
        // (there is no GET mapping, but the security filter chain runs before dispatch would 404,
        // so an unauthenticated GET is rejected first).
        mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }

    /**
     * Story 7.3, AC5/D-C: the recovery endpoints add no unauthenticated surface — they sit under
     * the blanket {@code /api/v1/**}.authenticated() rule exactly like every other endpoint, and
     * are reachable only with a (throwaway) JWT.
     */
    @Test
    void recoveryEndpoints_requireAThrowawayJwt_andAddNoUnauthenticatedSurface() throws Exception {
        mockMvc.perform(post("/api/v1/account/recovery/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"person@example.com\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/account/recovery/email/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"person@example.com\",\"code\":\"042817\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/account/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"person@example.com\"}"))
                .andExpect(status().isUnauthorized());
    }
}
