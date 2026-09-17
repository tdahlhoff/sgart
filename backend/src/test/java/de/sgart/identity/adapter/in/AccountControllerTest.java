package de.sgart.identity.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryProvisionedAccountRepository;
import de.sgart.identity.application.SendRecoveryCodeEmail;
import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
 * MockMvc slice over the real {@code AccountController}/application wiring, with the durable
 * repository swapped for an in-memory double — no live PostgreSQL and no live Keycloak (the
 * default {@code DeferredCreateAccount} stays wired). Proves AC1/AC3/AC4 end-to-end through REST:
 * zero-input, unauthenticated, idempotent, fail-fast (mirrors {@code DeviceControllerTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    private static final String VALID_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProvisionedAccountRepository provisionedAccountRepository;

    @Autowired
    private EmailRecoveryCodeStore emailRecoveryCodeStore;

    @Autowired
    private RecordingSendRecoveryCodeEmail sendRecoveryCodeEmail;

    @TestConfiguration
    static class InMemoryAdaptersConfig {

        @Bean
        @Primary
        ProvisionedAccountRepository testProvisionedAccountRepository() {
            return new InMemoryProvisionedAccountRepository();
        }

        @Bean
        @Primary
        EmailRecoveryCodeStore testEmailRecoveryCodeStore() {
            return new InMemoryEmailRecoveryCodeStore();
        }

        /** Captures the plaintext code that would have been emailed, so tests can confirm with it. */
        @Bean
        @Primary
        RecordingSendRecoveryCodeEmail testSendRecoveryCodeEmail() {
            return new RecordingSendRecoveryCodeEmail();
        }
    }

    static final class RecordingSendRecoveryCodeEmail implements SendRecoveryCodeEmail {
        final List<String> sentTo = new ArrayList<>();
        final List<String> sentCodes = new ArrayList<>();

        @Override
        public void send(String email, String code) {
            sentTo.add(email);
            sentCodes.add(code);
        }

        String lastCode() {
            return sentCodes.get(sentCodes.size() - 1);
        }

        void clear() {
            sentTo.clear();
            sentCodes.clear();
        }
    }

    @BeforeEach
    void clearSharedRepository() {
        // The @Primary in-memory doubles are singletons for the whole (shared) Spring context, so
        // state written by one test method would otherwise leak into the next.
        ((InMemoryProvisionedAccountRepository) provisionedAccountRepository).clear();
        ((InMemoryEmailRecoveryCodeStore) emailRecoveryCodeStore).clear();
        sendRecoveryCodeEmail.clear();
    }

    @Test
    void provision_withNoAuthorizationHeaderAtAll_returns204AndCreatesTheAccount() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKey\":\"" + VALID_PUBLIC_KEY + "\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isNoContent());

        InMemoryProvisionedAccountRepository repository =
                (InMemoryProvisionedAccountRepository) provisionedAccountRepository;
        assertThat(repository.findProvisionedBefore(Instant.now().plusSeconds(60))).hasSize(1);
    }

    @Test
    void provision_calledTwiceForTheSamePublicKey_createsExactlyOneRow() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKey\":\"" + VALID_PUBLIC_KEY + "\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKey\":\"" + VALID_PUBLIC_KEY + "\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isNoContent());

        InMemoryProvisionedAccountRepository repository =
                (InMemoryProvisionedAccountRepository) provisionedAccountRepository;
        assertThat(repository.findProvisionedBefore(Instant.now().plusSeconds(60))).hasSize(1);
    }

    @Test
    void provision_rejectsAMalformedPublicKeyWith400AndCreatesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKey\":\"not-valid!!!\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("account.publicKeyInvalid"));

        InMemoryProvisionedAccountRepository repository =
                (InMemoryProvisionedAccountRepository) provisionedAccountRepository;
        assertThat(repository.findProvisionedBefore(Instant.now().plusSeconds(60))).isEmpty();
    }

    @Test
    void provision_rejectsAMissingPublicKeyWith400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"ANDROID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("account.publicKeyRequired"));
    }

    @Test
    void attachEmail_returns202AndIssuesACode() throws Exception {
        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anna@example.com\"}"))
                .andExpect(status().isAccepted());

        assertThat(sendRecoveryCodeEmail.sentTo).containsExactly("anna@example.com");
    }

    @Test
    void attachEmail_rejectsAMalformedEmailWith400() throws Exception {
        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("account.recoveryEmailInvalid"));
    }

    @Test
    void confirmEmail_withCorrectCode_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anna@example.com\"}"))
                .andExpect(status().isAccepted());
        String code = sendRecoveryCodeEmail.lastCode();

        mockMvc.perform(post("/api/v1/account/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void confirmEmail_withWrongCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anna@example.com\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/account/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("account.recoveryCodeInvalid"));
    }

    @Test
    void detachEmail_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/account/email").with(jwt().jwt(jwt -> jwt.subject("anna-sub"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void requestRecoveryCode_withUnregisteredEmail_stillReturns202() throws Exception {
        mockMvc.perform(post("/api/v1/account/recovery/email")
                        .with(jwt().jwt(jwt -> jwt.subject("device-2-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isAccepted());

        assertThat(sendRecoveryCodeEmail.sentTo).isEmpty();
    }

    @Test
    void requestRecoveryCode_withoutAJwt_is401() throws Exception {
        mockMvc.perform(post("/api/v1/account/recovery/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void confirmRecovery_withUnknownEmail_returns400WithoutEnumeratingTheReason() throws Exception {
        mockMvc.perform(post("/api/v1/account/recovery/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject("device-2-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("account.recoveryCodeInvalid"));
    }
}
