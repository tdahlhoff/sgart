package de.sgart.identity.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.adapter.out.InMemoryMembershipNicknameRepository;
import de.sgart.identity.adapter.out.InMemoryProvisionedAccountRepository;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailThrottles;
import de.sgart.identity.adapter.out.RecoveryIssuanceExecutor;
import de.sgart.identity.application.AccountDetails;
import de.sgart.identity.application.DeleteAccount;
import de.sgart.identity.application.GetAccountDetails;
import de.sgart.identity.application.RebindAccountCredential;
import de.sgart.identity.application.RecoveryEmailDigester;
import de.sgart.identity.application.SendRecoveryCodeEmail;
import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MembershipNicknameRepository;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final String RECOVERABLE_EMAIL = "recoverable@example.test";
    private static final String RECOVERABLE_ACCOUNT_ID = "recoverable-sub";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProvisionedAccountRepository provisionedAccountRepository;

    @Autowired
    private EmailRecoveryCodeStore emailRecoveryCodeStore;

    @Autowired
    private RecordingSendRecoveryCodeEmail sendRecoveryCodeEmail;

    @Autowired
    private InMemoryRecoveryEmailThrottles recoveryEmailThrottles;

    @Autowired
    private RecoveryEmailBindingRepository recoveryEmailBindingRepository;

    @Autowired
    private RecoveryEmailDigester recoveryEmailDigester;

    @Autowired
    private RecordingGetAccountDetails getAccountDetails;

    @Autowired
    private RecordingDeleteAccount deleteAccount;

    @Autowired
    private RecordingRebindAccountCredential rebindAccountCredential;

    @TestConfiguration
    static class InMemoryAdaptersConfig {

        @Bean
        @Primary
        ProvisionedAccountRepository testProvisionedAccountRepository() {
            return new InMemoryProvisionedAccountRepository();
        }

        @Bean
        @Primary
        InMemoryMemberMappingRepository testMemberMappingRepository() {
            return new InMemoryMemberMappingRepository();
        }

        @Bean
        @Primary
        MembershipNicknameRepository testMembershipNicknameRepository(
                InMemoryMemberMappingRepository memberMappingRepository) {
            return new InMemoryMembershipNicknameRepository(memberMappingRepository);
        }

        @Bean
        @Primary
        RecoveryEmailBindingRepository testRecoveryEmailBindingRepository() {
            return new InMemoryRecoveryEmailBindingRepository();
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

        /** Runs the recovery issuance synchronously, so a test can read the mailed code right after the request. */
        @Bean
        @Primary
        RecoveryIssuanceExecutor testRecoveryIssuanceExecutor() {
            return new RecoveryIssuanceExecutor(Runnable::run);
        }

        /**
         * The default {@code DeferredGetAccountDetails}/{@code DeferredDeleteAccount}/{@code
         * DeferredRebindAccountCredential} stand-ins never find or record anything (no real
         * Keycloak account exists in this slice) — the recover-by-email end-to-end test needs a
         * throwaway account it can register details for, plus a way to observe the delete/rebind
         * the R1 rebind performs (Story 8.9).
         */
        @Bean
        @Primary
        RecordingGetAccountDetails testGetAccountDetails() {
            return new RecordingGetAccountDetails();
        }

        @Bean
        @Primary
        RecordingDeleteAccount testDeleteAccount() {
            return new RecordingDeleteAccount();
        }

        @Bean
        @Primary
        RecordingRebindAccountCredential testRebindAccountCredential() {
            return new RecordingRebindAccountCredential();
        }
    }

    static final class RecordingSendRecoveryCodeEmail implements SendRecoveryCodeEmail {
        final List<String> sentTo = new ArrayList<>();
        final List<String> sentCodes = new ArrayList<>();

        @Override
        public void sendAttachConfirmationCode(String address, String code) {
            record(address, code);
        }

        @Override
        public void sendRecoveryCode(String address, String code) {
            record(address, code);
        }

        private void record(String address, String code) {
            sentTo.add(address);
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

    /** Registers the throwaway device's current {@code username}/{@code publicKey}, as the real Admin API would answer. */
    static final class RecordingGetAccountDetails implements GetAccountDetails {
        private final Map<String, AccountDetails> byKeycloakUserId = new HashMap<>();

        void register(String keycloakUserId, String username, String publicKey) {
            byKeycloakUserId.put(keycloakUserId, new AccountDetails(username, publicKey));
        }

        @Override
        public Optional<AccountDetails> findById(KeycloakUserId keycloakUserId) {
            return Optional.ofNullable(byKeycloakUserId.get(keycloakUserId.value()));
        }

        void clear() {
            byKeycloakUserId.clear();
        }
    }

    static final class RecordingDeleteAccount implements DeleteAccount {
        final List<String> deletedIds = new ArrayList<>();

        @Override
        public void delete(KeycloakUserId keycloakUserId) {
            deletedIds.add(keycloakUserId.value());
        }

        void clear() {
            deletedIds.clear();
        }
    }

    static final class RecordingRebindAccountCredential implements RebindAccountCredential {
        record Rebind(String keycloakUserId, String username, String publicKey) {}

        final List<Rebind> rebinds = new ArrayList<>();
        boolean shouldFail;

        @Override
        public void rebind(KeycloakUserId keycloakUserId, String username, String publicKey) {
            if (shouldFail) {
                throw new IllegalStateException("keycloak unavailable");
            }
            rebinds.add(new Rebind(keycloakUserId.value(), username, publicKey));
        }

        void clear() {
            rebinds.clear();
            shouldFail = false;
        }
    }

    @BeforeEach
    void clearSharedRepository() {
        // The @Primary in-memory doubles are singletons for the whole (shared) Spring context, so
        // state written by one test method would otherwise leak into the next.
        ((InMemoryProvisionedAccountRepository) provisionedAccountRepository).clear();
        ((InMemoryEmailRecoveryCodeStore) emailRecoveryCodeStore).clear();
        ((InMemoryRecoveryEmailBindingRepository) recoveryEmailBindingRepository).clear();
        recoveryEmailThrottles.clear();
        confirmBindingOf(RECOVERABLE_ACCOUNT_ID, RECOVERABLE_EMAIL);
        sendRecoveryCodeEmail.clear();
        getAccountDetails.clear();
        deleteAccount.clear();
        rebindAccountCredential.clear();
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
    void attachEmail_secondAttachWithinTheCooldown_returns429() throws Exception {
        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("throttled-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"throttled@example.com\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("throttled-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"throttled@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("account.recoveryCodeRateLimited"));

        assertThat(sendRecoveryCodeEmail.sentTo).containsExactly("throttled@example.com");
    }

    @Test
    void attachEmail_detachThenReattachWithinTheCooldown_isStillThrottled() throws Exception {
        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("looping-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"looping@example.com\"}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(delete("/api/v1/account/email").with(jwt().jwt(jwt -> jwt.subject("looping-sub"))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("looping-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"looping@example.com\"}"))
                .andExpect(status().isTooManyRequests());

        assertThat(sendRecoveryCodeEmail.sentTo).containsExactly("looping@example.com");
    }

    @Test
    void attachEmail_toAnAddressAnotherAccountAlreadyConfirmed_answersTheSameAsForAFreshAddress() throws Exception {
        attachAndConfirm("owner-sub", "shared@example.test");

        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("second-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"shared@example.test\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(sendRecoveryCodeEmail.sentTo).containsExactly("shared@example.test", "shared@example.test");
    }

    @Test
    void attachEmail_afterThePerAddressMailBudgetIsUsedUp_stillAnswers202ButSendsNothing() throws Exception {
        for (int attacher = 0; attacher < 3; attacher++) {
            String subject = "attacher-" + attacher;
            mockMvc.perform(post("/api/v1/account/email")
                            .with(jwt().jwt(jwt -> jwt.subject(subject)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"popular@example.test\"}"))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("attacher-3")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"popular@example.test\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(sendRecoveryCodeEmail.sentTo).hasSize(3);
    }

    @Test
    void recoveryEmailStatus_afterConfirm_returnsTheMaskedHint() throws Exception {
        attachAndConfirm("anna-sub", "anna@example.test");

        mockMvc.perform(get("/api/v1/account/email").with(jwt().jwt(jwt -> jwt.subject("anna-sub"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressHint").value("a***@example.test"));
    }

    @Test
    void recoveryEmailStatus_whileOnlyPending_returnsANullHint() throws Exception {
        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject("anna-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anna@example.test\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/account/email").with(jwt().jwt(jwt -> jwt.subject("anna-sub"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressHint").value(nullValue()));
    }

    @Test
    void recoveryEmailStatus_withoutAJwt_is401() throws Exception {
        mockMvc.perform(get("/api/v1/account/email")).andExpect(status().isUnauthorized());
    }

    private void attachAndConfirm(String subject, String address) throws Exception {
        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\"}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/account/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + sendRecoveryCodeEmail.lastCode() + "\"}"))
                .andExpect(status().isNoContent());
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
    void requestRecoveryCode_calledTwiceForARegisteredEmail_bothReturn202ButOnlyOneCodeIsSent() throws Exception {
        mockMvc.perform(post("/api/v1/account/recovery/email")
                        .with(jwt().jwt(jwt -> jwt.subject("device-2-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + RECOVERABLE_EMAIL + "\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/account/recovery/email")
                        .with(jwt().jwt(jwt -> jwt.subject("device-2-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + RECOVERABLE_EMAIL + "\"}"))
                .andExpect(status().isAccepted());

        // The second request is within the cooldown for the same address — the constant
        // 202 hides it, but only the first request actually sent a code.
        assertThat(sendRecoveryCodeEmail.sentTo).containsExactly(RECOVERABLE_EMAIL);
    }

    @Test
    void requestRecoveryCode_afterAttachWithinTheCooldown_stillSendsBecauseTheBudgetsAreSeparate() throws Exception {
        mockMvc.perform(post("/api/v1/account/email")
                        .with(jwt().jwt(jwt -> jwt.subject(RECOVERABLE_ACCOUNT_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"attacher@example.com\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/account/recovery/email")
                        .with(jwt().jwt(jwt -> jwt.subject("device-2-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + RECOVERABLE_EMAIL + "\"}"))
                .andExpect(status().isAccepted());

        assertThat(sendRecoveryCodeEmail.sentTo)
                .containsExactly("attacher@example.com", RECOVERABLE_EMAIL);
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

    @Test
    void confirmRecovery_whenTheCallersAccountNoLongerExists_returns401SoTheAppReSignsIn() throws Exception {
        String code = requestRecoveryCodeAsThrowaway("device-2-sub");

        mockMvc.perform(post("/api/v1/account/recovery/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject("device-2-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + RECOVERABLE_EMAIL + "\",\"code\":\"" + code
                                + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    @Test
    void confirmRecovery_whenTheRebindFails_returns503AndKeepsTheCodeForARetry() throws Exception {
        String throwawayAccountId = "device-2-sub";
        getAccountDetails.register(throwawayAccountId, "throwaway-username", "throwaway-public-key");
        String code = requestRecoveryCodeAsThrowaway(throwawayAccountId);
        rebindAccountCredential.shouldFail = true;

        mockMvc.perform(post("/api/v1/account/recovery/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject(throwawayAccountId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + RECOVERABLE_EMAIL + "\",\"code\":\"" + code
                                + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("account.recoveryRebindFailed"));

        assertThat(emailRecoveryCodeStore.find(recoveryCodeSubjectOf(RECOVERABLE_EMAIL), RecoveryCodePurpose.RECOVER))
                .isPresent();
    }

    private void confirmBindingOf(String accountId, String address) {
        RecoveryEmailDigest digest = recoveryEmailDigester.digest(address);
        KeycloakUserId account = new KeycloakUserId(accountId);
        recoveryEmailBindingRepository.savePending(
                RecoveryEmailBinding.pending(digest, account, RecoveryEmailHint.masking(address), Instant.now()));
        recoveryEmailBindingRepository.confirm(
                recoveryEmailBindingRepository.findPendingFor(account).orElseThrow().confirm(Instant.now()));
    }

    private RecoveryCodeSubject recoveryCodeSubjectOf(String address) {
        return RecoveryCodeSubject.forAddress(recoveryEmailDigester.digest(address));
    }

    private String requestRecoveryCodeAsThrowaway(String throwawayAccountId) throws Exception {
        mockMvc.perform(post("/api/v1/account/recovery/email")
                        .with(jwt().jwt(jwt -> jwt.subject(throwawayAccountId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + RECOVERABLE_EMAIL + "\"}"))
                .andExpect(status().isAccepted());
        return sendRecoveryCodeEmail.lastCode();
    }

    @Test
    void recoverByEmail_requestThenConfirmWithTheEmailedCode_rebindsTheTargetAccount() throws Exception {
        String throwawayAccountId = "device-2-sub";
        KeycloakUserId throwaway = new KeycloakUserId(throwawayAccountId);
        getAccountDetails.register(throwawayAccountId, "throwaway-username", "throwaway-public-key");
        ((InMemoryProvisionedAccountRepository) provisionedAccountRepository)
                .recordIfAbsent(throwaway, Instant.now());

        mockMvc.perform(post("/api/v1/account/recovery/email")
                        .with(jwt().jwt(jwt -> jwt.subject(throwawayAccountId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + RECOVERABLE_EMAIL + "\"}"))
                .andExpect(status().isAccepted());
        assertThat(sendRecoveryCodeEmail.sentCodes).hasSize(1);
        String code = sendRecoveryCodeEmail.lastCode();

        mockMvc.perform(post("/api/v1/account/recovery/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject(throwawayAccountId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + RECOVERABLE_EMAIL + "\",\"code\":\"" + code
                                + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(deleteAccount.deletedIds).containsExactly(throwawayAccountId);
        assertThat(rebindAccountCredential.rebinds).hasSize(1);
        RecordingRebindAccountCredential.Rebind rebind = rebindAccountCredential.rebinds.get(0);
        assertThat(rebind.keycloakUserId()).isEqualTo(RECOVERABLE_ACCOUNT_ID);
        assertThat(rebind.username()).isEqualTo("throwaway-username");
        assertThat(rebind.publicKey()).isEqualTo("throwaway-public-key");

        // The de-link: the RECOVER code is consumed and the throwaway's provisioned-shell row is gone.
        assertThat(emailRecoveryCodeStore.find(recoveryCodeSubjectOf(RECOVERABLE_EMAIL), RecoveryCodePurpose.RECOVER))
                .isEmpty();
        assertThat(((InMemoryProvisionedAccountRepository) provisionedAccountRepository).contains(throwaway))
                .isFalse();
    }

    @Test
    void confirmRecovery_withSeveralCandidateAccounts_answers200WithTheCandidatesAndKeepsTheCode() throws Exception {
        confirmBindingOf("second-sub", RECOVERABLE_EMAIL);
        String code = requestRecoveryCodeAsThrowaway("device-2-sub");

        mockMvc.perform(post("/api/v1/account/recovery/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject("device-2-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + RECOVERABLE_EMAIL + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[*].accountId").value(
                        org.hamcrest.Matchers.containsInAnyOrder(RECOVERABLE_ACCOUNT_ID, "second-sub")))
                .andExpect(jsonPath("$.candidates[0].households").isArray());

        assertThat(emailRecoveryCodeStore.find(recoveryCodeSubjectOf(RECOVERABLE_EMAIL), RecoveryCodePurpose.RECOVER))
                .isPresent();
        assertThat(rebindAccountCredential.rebinds).isEmpty();
    }

    @Test
    void confirmRecovery_withAChosenCandidateAccount_answers204AndRebindsExactlyThatAccount() throws Exception {
        confirmBindingOf("second-sub", RECOVERABLE_EMAIL);
        getAccountDetails.register("device-2-sub", "throwaway-username", "throwaway-public-key");
        String code = requestRecoveryCodeAsThrowaway("device-2-sub");

        mockMvc.perform(post("/api/v1/account/recovery/email/confirm")
                        .with(jwt().jwt(jwt -> jwt.subject("device-2-sub")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + RECOVERABLE_EMAIL + "\",\"code\":\"" + code
                                + "\",\"accountId\":\"second-sub\"}"))
                .andExpect(status().isNoContent());

        assertThat(rebindAccountCredential.rebinds).hasSize(1);
        assertThat(rebindAccountCredential.rebinds.get(0).keycloakUserId()).isEqualTo("second-sub");
    }
}
