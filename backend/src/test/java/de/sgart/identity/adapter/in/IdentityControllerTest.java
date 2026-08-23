package de.sgart.identity.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test using {@code spring-security-test}'s mock {@code jwt()} post-processor — no
 * live Keycloak needed (Story 1.4 Dev Notes). Proves AC1 (validation + {@code sub}-only extraction
 * behind a single seam) and AC2 (live claims, nothing persisted) end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdentityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void me_returnsTheLiveCallerIdentityFromAValidJwtWithKeycloakUserIdEqualToSub() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")
                                .claim("name", "Anna Testperson")
                                .claim("email", "anna@example.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keycloakUserId").value("anna-sub"))
                .andExpect(jsonPath("$.displayName").value("Anna Testperson"))
                .andExpect(jsonPath("$.email").value("anna@example.test"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void me_fallsBackToPreferredUsernameWhenNoNameClaimIsPresent() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me")
                        .with(jwt().jwt(jwt -> jwt.subject("ben-sub")
                                .claim("preferred_username", "ben@example.test")
                                .claim("email", "ben@example.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("ben@example.test"));
    }

    @Test
    void me_rejectsARequestWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void me_rejectsARequestWithAMalformedToken() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me").header("Authorization", "Bearer not-a-valid-jwt"))
                .andExpect(status().isUnauthorized());
    }
}
