package de.sgart.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.identity.adapter.out.RecoveryIssuanceExecutor;
import de.sgart.identity.application.AccountDetails;
import de.sgart.identity.application.DeleteAccount;
import de.sgart.identity.application.GetAccountDetails;
import de.sgart.identity.application.RebindAccountCredential;
import de.sgart.identity.application.SendRecoveryCodeEmail;
import de.sgart.identity.domain.KeycloakUserId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end proof of recover-by-email over HTTP against real PostgreSQL: a person attaches and
 * confirms a recovery address on account A, then a fresh device (a throwaway account T) requests
 * and confirms a recovery code for the same address and is rebound onto A. Keycloak is replaced by
 * in-memory fakes and the mail port by a capturing one; the issuance executor is synchronous so the
 * mailed code is readable straight after the request. Finally, no table holds the plaintext address.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RecoverByEmailEndToEndTest {

    private static final String LOCAL_PART = "end.to.end";
    private static final String DOMAIN = "example.test";
    private static final String ADDRESS = LOCAL_PART + "@" + DOMAIN;
    private static final String MASKED_ADDRESS = "e***@e***.test";
    private static final String ACCOUNT_A = "account-a";
    private static final String THROWAWAY_T = "throwaway-t";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void useTheContainerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private CapturingMail capturingMail;

    @Autowired
    private RecordingRebind recordingRebind;

    @Autowired
    private RecordingDelete recordingDelete;

    @TestConfiguration
    static class FakeBoundariesConfig {

        @Bean
        @Primary
        RecoveryIssuanceExecutor synchronousRecoveryIssuanceExecutor() {
            return new RecoveryIssuanceExecutor(Runnable::run);
        }

        @Bean
        @Primary
        CapturingMail capturingMail() {
            return new CapturingMail();
        }

        @Bean
        @Primary
        RecordingRebind recordingRebind() {
            return new RecordingRebind();
        }

        @Bean
        @Primary
        RecordingDelete recordingDelete() {
            return new RecordingDelete();
        }

        @Bean
        @Primary
        GetAccountDetails throwawayAccountDetails() {
            return keycloakUserId -> THROWAWAY_T.equals(keycloakUserId.value())
                    ? Optional.of(new AccountDetails("throwaway-username", "throwaway-public-key"))
                    : Optional.empty();
        }
    }

    static final class CapturingMail implements SendRecoveryCodeEmail {
        final List<String> attachRecipients = new ArrayList<>();
        final List<String> attachCodes = new ArrayList<>();
        final List<String> recoveryRecipients = new ArrayList<>();
        final List<String> recoveryCodes = new ArrayList<>();

        @Override
        public void sendAttachConfirmationCode(String address, String code) {
            attachRecipients.add(address);
            attachCodes.add(code);
        }

        @Override
        public void sendRecoveryCode(String address, String code) {
            recoveryRecipients.add(address);
            recoveryCodes.add(code);
        }
    }

    static final class RecordingRebind implements RebindAccountCredential {
        record Rebind(String keycloakUserId, String username, String publicKey) {}

        final List<Rebind> rebinds = new ArrayList<>();

        @Override
        public void rebind(KeycloakUserId keycloakUserId, String username, String publicKey) {
            rebinds.add(new Rebind(keycloakUserId.value(), username, publicKey));
        }
    }

    static final class RecordingDelete implements DeleteAccount {
        final List<String> deletedAccountIds = new ArrayList<>();

        @Override
        public void delete(KeycloakUserId keycloakUserId) {
            deletedAccountIds.add(keycloakUserId.value());
        }
    }

    @Test
    void aFreshDeviceRecoversTheAccountThatConfirmedTheAddress_andNoTableHoldsThePlaintextAddress() throws Exception {
        attachAndConfirmAs(ACCOUNT_A);

        mockMvc.perform(post("/api/v1/account/recovery/email")
                        .with(jwt().jwt(jwt -> jwt.subject(THROWAWAY_T)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADDRESS + "\"}"))
                .andExpect(status().isAccepted());
        assertThat(capturingMail.recoveryCodes).hasSize(1);
        assertThat(capturingMail.recoveryRecipients).containsExactly(ADDRESS);
        assertNoTableHoldsThePlaintextAddress();

        mockMvc.perform(post("/api/v1/account/recovery/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject(THROWAWAY_T)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADDRESS + "\",\"code\":\"" + capturingMail.recoveryCodes.get(0)
                                + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(recordingDelete.deletedAccountIds).containsExactly(THROWAWAY_T);
        assertThat(recordingRebind.rebinds)
                .containsExactly(
                        new RecordingRebind.Rebind(THROWAWAY_T, "recovering-throwaway-username", "throwaway-public-key"),
                        new RecordingRebind.Rebind(ACCOUNT_A, "throwaway-username", "throwaway-public-key"));
        assertNoTableHoldsThePlaintextAddress();
    }

    private void attachAndConfirmAs(String accountId) throws Exception {
        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject(accountId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADDRESS + "\"}"))
                .andExpect(status().isAccepted());
        assertThat(capturingMail.attachCodes).hasSize(1);
        assertThat(capturingMail.attachRecipients).containsExactly(ADDRESS);
        assertThat(storedAddressHints()).containsExactly(MASKED_ADDRESS);
        assertNoTableHoldsThePlaintextAddress();
        mockMvc.perform(post("/api/v1/account/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject(accountId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + capturingMail.attachCodes.get(0) + "\"}"))
                .andExpect(status().isNoContent());
    }

    private List<String> storedAddressHints() {
        return jdbcClient.sql("SELECT address_hint FROM recovery_email_binding").query(String.class).list();
    }

    /**
     * The masked hint keeps only the first character of the domain name and the top-level domain, so
     * no row of any table, the hint included, may contain the address, its local part or its domain.
     */
    private void assertNoTableHoldsThePlaintextAddress() {
        assertThat(everyStoredRowAsText())
                .noneMatch(row -> row.contains(ADDRESS))
                .noneMatch(row -> row.contains(LOCAL_PART))
                .noneMatch(row -> row.contains(DOMAIN));
    }

    private List<String> everyStoredRowAsText() {
        List<String> rows = new ArrayList<>();
        List<String> tableNames = jdbcClient
                .sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                .query(String.class)
                .list();
        for (String tableName : tableNames) {
            rows.addAll(jdbcClient
                    .sql("SELECT t::text FROM \"" + tableName + "\" t")
                    .query(String.class)
                    .list());
        }
        return rows;
    }
}
