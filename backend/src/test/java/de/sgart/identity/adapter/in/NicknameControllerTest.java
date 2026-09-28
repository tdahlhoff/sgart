package de.sgart.identity.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.adapter.out.InMemoryMembershipNicknameRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.identity.domain.MembershipNicknameRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
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
 * MockMvc slice over the real {@code NicknameController}/application wiring, with the durable
 * repositories swapped for in-memory doubles — no live PostgreSQL. Proves Story 8.3's write path:
 * own nickname set/updated ({@code 204}), 4xx on invalid input, 403 for a non-member, and that
 * {@code keycloakUserId} is taken only from the JWT {@code sub} (mirrors {@code
 * DeviceControllerTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class NicknameControllerTest {

    private static final String ANNA_SUB = "anna-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberMappingRepository mappingRepository;

    @Autowired
    private MembershipNicknameRepository nicknameRepository;

    @TestConfiguration
    static class InMemoryAdaptersConfig {

        @Bean
        @Primary
        MemberMappingRepository testMemberMappingRepository() {
            return new InMemoryMemberMappingRepository();
        }

        @Bean
        @Primary
        MembershipNicknameRepository testMembershipNicknameRepository(MemberMappingRepository memberMappingRepository) {
            return new InMemoryMembershipNicknameRepository((InMemoryMemberMappingRepository) memberMappingRepository);
        }
    }

    @Test
    void set_returns204AndStoresTheCallersOwnNickname() throws Exception {
        HouseholdId householdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(ANNA_SUB)));

        mockMvc.perform(put("/api/v1/identity/households/{householdId}/nickname", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Papa\"}"))
                .andExpect(status().isNoContent());

        assertThat(nicknameRepository.find(new KeycloakUserId(ANNA_SUB), householdId)).contains("Papa");
    }

    @Test
    void set_updatingAgainOverwritesThePreviousNickname() throws Exception {
        HouseholdId householdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(ANNA_SUB)));
        mockMvc.perform(put("/api/v1/identity/households/{householdId}/nickname", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Papa\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/v1/identity/households/{householdId}/nickname", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Timo\"}"))
                .andExpect(status().isNoContent());

        assertThat(nicknameRepository.find(new KeycloakUserId(ANNA_SUB), householdId)).contains("Timo");
    }

    @Test
    void set_rejectsABlankNicknameWith400() throws Exception {
        HouseholdId householdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(ANNA_SUB)));

        mockMvc.perform(put("/api/v1/identity/households/{householdId}/nickname", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("nickname.required"));
    }

    @Test
    void set_rejectsAnOverLengthNicknameWith400() throws Exception {
        HouseholdId householdId = HouseholdId.generate();
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId(ANNA_SUB)));
        String tooLong = "a".repeat(200);

        mockMvc.perform(put("/api/v1/identity/households/{householdId}/nickname", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("nickname.tooLong"));
    }

    @Test
    void set_rejectsACallerWhoIsNotAMemberOfTheHouseholdWith403() throws Exception {
        HouseholdId householdId = HouseholdId.generate();

        mockMvc.perform(put("/api/v1/identity/households/{householdId}/nickname", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ANNA_SUB)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Papa\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void set_requiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/v1/identity/households/{householdId}/nickname", HouseholdId.generate().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Papa\"}"))
                .andExpect(status().isUnauthorized());
    }
}
