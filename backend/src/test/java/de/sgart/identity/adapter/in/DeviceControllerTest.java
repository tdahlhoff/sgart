package de.sgart.identity.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.identity.adapter.out.InMemoryDeviceTokenRepository;
import de.sgart.identity.domain.DeviceTokenRepository;
import de.sgart.identity.domain.KeycloakUserId;
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
 * MockMvc slice over the real {@code DeviceController}/application wiring, with the durable
 * repository swapped for an in-memory double — no live PostgreSQL. Proves AC5 end-to-end through
 * REST: register/refresh ({@code 204}), unregister ({@code 204}), and that {@code keycloakUserId}
 * is taken only from the JWT {@code sub} (mirrors {@code MemberControllerTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceControllerTest {

    private static final String ANNA_SUB = "anna-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @TestConfiguration
    static class InMemoryAdaptersConfig {

        @Bean
        @Primary
        DeviceTokenRepository testDeviceTokenRepository() {
            return new InMemoryDeviceTokenRepository();
        }
    }

    @Test
    void register_returns204AndStoresTheTokenUnderTheCallersOwnKeycloakUserId() throws Exception {
        mockMvc.perform(post("/api/v1/devices")
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-1\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isNoContent());

        assertThat(deviceTokenRepository.findByKeycloakUserId(new KeycloakUserId(ANNA_SUB)))
                .singleElement()
                .satisfies(deviceToken -> assertThat(deviceToken.token()).isEqualTo("fcm-token-1"));
    }

    @Test
    void register_reRegisteringTheSameTokenIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/v1/devices")
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-1\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/devices")
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-1\",\"platform\":\"IOS\"}"))
                .andExpect(status().isNoContent());

        assertThat(deviceTokenRepository.findByKeycloakUserId(new KeycloakUserId(ANNA_SUB))).hasSize(1);
    }

    @Test
    void register_rejectsABlankTokenWith400() throws Exception {
        mockMvc.perform(post("/api/v1/devices")
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("device.tokenRequired"));
    }

    @Test
    void unregister_returns204AndRemovesTheToken() throws Exception {
        mockMvc.perform(post("/api/v1/devices")
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-1\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/devices")
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-1\"}"))
                .andExpect(status().isNoContent());

        assertThat(deviceTokenRepository.findByToken("fcm-token-1")).isEmpty();
    }

    @Test
    void requestsWithoutATokenAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-1\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isUnauthorized());
    }
}
