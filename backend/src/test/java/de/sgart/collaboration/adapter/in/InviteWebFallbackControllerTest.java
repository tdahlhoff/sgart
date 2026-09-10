package de.sgart.collaboration.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc slice proving the served web-fallback route (Story 4.6, D3, AC2/AC5): {@code GET
 * /invite} is reachable <strong>without authentication</strong> (unlike {@code /api/v1/**}), serves
 * the static accept-only page as {@code text/html}, and the page references the accept endpoint
 * while exposing no navigation into the app's daily surfaces (AC5). The in-browser Keycloak
 * PKCE round-trip itself is out of this Java-stack test's reach (documented follow-up, AC7).
 */
@SpringBootTest
@AutoConfigureMockMvc
class InviteWebFallbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getInvite_isReachableWithoutAuthenticationAndServesTheAcceptOnlyPage() throws Exception {
        MvcResult result = mockMvc.perform(get("/invite"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("/accept");
        assertThat(body).doesNotContain("shopping-list", "trip", "household-list");
    }

    @Test
    void getInviteConfigJson_isReachableWithoutAuthenticationAndCarriesThePublicPkceConfig() throws Exception {
        mockMvc.perform(get("/invite/config.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.authorizeUrl").value(org.hamcrest.Matchers.endsWith("/protocol/openid-connect/auth")))
                .andExpect(jsonPath("$.tokenUrl").value(org.hamcrest.Matchers.endsWith("/protocol/openid-connect/token")))
                .andExpect(jsonPath("$.clientId").isNotEmpty());
    }
}
