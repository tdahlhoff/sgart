package de.sgart.identity.adapter.out;

import de.sgart.identity.application.AttachRecoveryEmail;
import de.sgart.identity.application.ConfirmEmailRecovery;
import de.sgart.identity.application.ConfirmRecoveryEmail;
import de.sgart.identity.application.CreateAccount;
import de.sgart.identity.application.DeleteAccount;
import de.sgart.identity.application.DetachRecoveryEmail;
import de.sgart.identity.application.FindAccountByEmail;
import de.sgart.identity.application.FindHouseholdMemberByEmail;
import de.sgart.identity.application.GetAccountDetails;
import de.sgart.identity.application.GetConsentStatus;
import de.sgart.identity.application.RecordConsent;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.identity.application.IssueMemberIdentity;
import de.sgart.identity.application.ProvisionAccount;
import de.sgart.identity.application.PruneDeviceToken;
import de.sgart.identity.application.RebindAccountCredential;
import de.sgart.identity.application.RecoveryCodeHasher;
import de.sgart.identity.application.RegisterDeviceToken;
import de.sgart.identity.application.RequestEmailRecoveryCode;
import de.sgart.identity.application.ResolveHouseholdPushTargets;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.application.RetractMembership;
import de.sgart.identity.application.SendRecoveryCodeEmail;
import de.sgart.identity.application.SetAccountEmail;
import de.sgart.identity.application.SweepNeverActivatedAccounts;
import de.sgart.identity.application.UnregisterDeviceToken;
import de.sgart.identity.domain.AccountConsentRepository;
import de.sgart.identity.domain.DeviceTokenRepository;
import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

/**
 * Wires the Identity ACL's durable mapping repository and its application-layer ports (Story 1.6:
 * the issue/write path and the mapping table, deferred from Story 1.4). Building the {@code
 * JdbcClient}-backed repository performs no I/O, so {@code contextLoads()} survives Postgres being
 * down.
 */
@Configuration
public class IdentityBeansConfig {

    @Bean
    MemberMappingRepository memberMappingRepository(JdbcClient jdbcClient) {
        return new JdbcMemberMappingRepository(jdbcClient);
    }

    @Bean
    IssueMemberIdentity issueMemberIdentity(MemberMappingRepository memberMappingRepository) {
        return new IssueMemberIdentity(memberMappingRepository);
    }

    @Bean
    ListHouseholdsForCaller listHouseholdsForCaller(MemberMappingRepository memberMappingRepository) {
        return new ListHouseholdsForCaller(memberMappingRepository);
    }

    @Bean
    ResolveMemberIdentity resolveMemberIdentity(MemberMappingRepository memberMappingRepository) {
        return new ResolveMemberIdentity(memberMappingRepository);
    }

    /**
     * The real Story 4.6 (D4, AC4) lookup — active only when {@code
     * sgart.identity.keycloak-admin.enabled=true} is set explicitly. Building the {@link RestClient}
     * performs no I/O (lazy, like every other adapter in this class); the first Admin API call is
     * what actually reaches Keycloak.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sgart.identity.keycloak-admin", name = "enabled", havingValue = "true")
    FindHouseholdMemberByEmail keycloakAdminFindHouseholdMemberByEmail(
            MemberMappingRepository memberMappingRepository,
            @Value("${sgart.identity.keycloak-admin.base-url}") String baseUrl,
            @Value("${sgart.identity.keycloak-admin.realm}") String realm,
            @Value("${sgart.identity.keycloak-admin.client-id}") String clientId,
            @Value("${sgart.identity.keycloak-admin.client-secret}") String clientSecret) {
        return new KeycloakAdminFindHouseholdMemberByEmail(
                RestClient.builder().baseUrl(baseUrl).build(), memberMappingRepository, realm, clientId, clientSecret);
    }

    /**
     * The wired default — active whenever the Keycloak Admin adapter above is not (Story 4.1).
     * Gated on the same property (its {@code false}/absent case) rather than {@code
     * @ConditionalOnMissingBean}, so the wiring does not depend on this method being declared after
     * the Keycloak bean ({@code @ConditionalOnMissingBean} is bean-declaration-order sensitive in a
     * user {@code @Configuration} — Story 4.6 review).
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.keycloak-admin",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    FindHouseholdMemberByEmail deferredFindHouseholdMemberByEmail() {
        return new DeferredFindHouseholdMemberByEmail();
    }

    @Bean
    RetractMembership retractMembership(MemberMappingRepository memberMappingRepository) {
        return new RetractMembership(memberMappingRepository);
    }

    @Bean
    DeviceTokenRepository deviceTokenRepository(JdbcClient jdbcClient) {
        return new JdbcDeviceTokenRepository(jdbcClient);
    }

    @Bean
    RegisterDeviceToken registerDeviceToken(DeviceTokenRepository deviceTokenRepository, Clock clock) {
        return new RegisterDeviceToken(deviceTokenRepository, clock);
    }

    @Bean
    UnregisterDeviceToken unregisterDeviceToken(DeviceTokenRepository deviceTokenRepository) {
        return new UnregisterDeviceToken(deviceTokenRepository);
    }

    @Bean
    PruneDeviceToken pruneDeviceToken(DeviceTokenRepository deviceTokenRepository) {
        return new PruneDeviceToken(deviceTokenRepository);
    }

    @Bean
    ResolveHouseholdPushTargets resolveHouseholdPushTargets(
            MemberMappingRepository memberMappingRepository, DeviceTokenRepository deviceTokenRepository) {
        return new ResolveHouseholdPushTargets(memberMappingRepository, deviceTokenRepository);
    }

    @Bean
    ProvisionedAccountRepository provisionedAccountRepository(JdbcClient jdbcClient) {
        return new JdbcProvisionedAccountRepository(jdbcClient);
    }

    @Bean
    ProvisionAccount provisionAccount(
            CreateAccount createAccount, ProvisionedAccountRepository provisionedAccountRepository, Clock clock) {
        return new ProvisionAccount(createAccount, provisionedAccountRepository, clock);
    }

    @Bean
    SweepNeverActivatedAccounts sweepNeverActivatedAccounts(
            ProvisionedAccountRepository provisionedAccountRepository,
            MemberMappingRepository memberMappingRepository,
            DeleteAccount deleteAccount,
            GetAccountDetails getAccountDetails,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            Clock clock,
            @Value("${sgart.identity.provisioning.retention-days}") long retentionDays) {
        return new SweepNeverActivatedAccounts(
                provisionedAccountRepository,
                memberMappingRepository,
                deleteAccount,
                getAccountDetails,
                emailRecoveryCodeStore,
                clock,
                Duration.ofDays(retentionDays));
    }

    /**
     * The real Story 7.1 Admin adapter for both {@link CreateAccount} and {@link DeleteAccount} —
     * one Keycloak "manage an account" class implementing both ports (DRY over the shared
     * client-credentials token fetch). Active only when {@code
     * sgart.identity.keycloak-admin.enabled=true}; shares that flag and its base-url/realm/
     * client-id/client-secret with the Story 4.6 lookup adapter below (both talk to the same
     * {@code sgart-admin} confidential client). Building the {@link RestClient} performs no I/O.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sgart.identity.keycloak-admin", name = "enabled", havingValue = "true")
    KeycloakAdminCreateAccount keycloakAdminCreateAccount(
            @Value("${sgart.identity.keycloak-admin.base-url}") String baseUrl,
            @Value("${sgart.identity.keycloak-admin.realm}") String realm,
            @Value("${sgart.identity.keycloak-admin.client-id}") String clientId,
            @Value("${sgart.identity.keycloak-admin.client-secret}") String clientSecret) {
        return new KeycloakAdminCreateAccount(RestClient.builder().baseUrl(baseUrl).build(), realm, clientId, clientSecret);
    }

    /**
     * The wired defaults — active whenever the Keycloak Admin adapter above is not (the same
     * gating as {@link #deferredFindHouseholdMemberByEmail()}, for the same reason).
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.keycloak-admin",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    DeferredCreateAccount deferredCreateAccount() {
        return new DeferredCreateAccount();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.keycloak-admin",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    DeferredDeleteAccount deferredDeleteAccount() {
        return new DeferredDeleteAccount();
    }

    // --- Story 7.3: recover by email (opt-in) -------------------------------------------------
    //
    // No separate @Bean methods wrap keycloakAdminCreateAccount() for SetAccountEmail/
    // RebindAccountCredential/FindAccountByEmail/GetAccountDetails: Spring already matches that
    // single bean (declared as the concrete KeycloakAdminCreateAccount type) against every
    // interface it implements when another @Bean method asks for one, exactly like CreateAccount/
    // DeleteAccount above — an extra per-interface wrapper method would register the *same*
    // instance under additional bean names, and once Spring resolves one by its actual runtime
    // type it becomes an ambiguous extra candidate for every other interface that type implements.

    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.keycloak-admin", name = "enabled", havingValue = "false", matchIfMissing = true)
    DeferredSetAccountEmail deferredSetAccountEmail() {
        return new DeferredSetAccountEmail();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.keycloak-admin", name = "enabled", havingValue = "false", matchIfMissing = true)
    DeferredRebindAccountCredential deferredRebindAccountCredential() {
        return new DeferredRebindAccountCredential();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.keycloak-admin", name = "enabled", havingValue = "false", matchIfMissing = true)
    DeferredFindAccountByEmail deferredFindAccountByEmail() {
        return new DeferredFindAccountByEmail();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.keycloak-admin", name = "enabled", havingValue = "false", matchIfMissing = true)
    DeferredGetAccountDetails deferredGetAccountDetails() {
        return new DeferredGetAccountDetails();
    }

    @Bean
    EmailRecoveryCodeStore emailRecoveryCodeStore(JdbcClient jdbcClient) {
        return new JdbcEmailRecoveryCodeStore(jdbcClient);
    }

    @Bean
    RecoveryCodeHasher recoveryCodeHasher(
            @Value("${sgart.identity.email-recovery.code-hmac-secret}") String secret) {
        return new HmacSha256RecoveryCodeHasher(secret);
    }

    /**
     * The real Story 7.3 SMTP adapter (design §3.1) — active only when {@code
     * sgart.identity.mail.enabled=true}. Building the {@link JavaMailSender} performs no I/O; only
     * the first {@code send} reaches an SMTP server.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sgart.identity.mail", name = "enabled", havingValue = "true")
    SendRecoveryCodeEmail javaMailSenderRecoveryCodeEmail(
            JavaMailSender javaMailSender, @Value("${sgart.identity.mail.from}") String fromAddress) {
        return new JavaMailSenderRecoveryCodeEmail(javaMailSender, fromAddress);
    }

    /** The wired default — no build needs an SMTP server (design §3.1, AC6). */
    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.mail", name = "enabled", havingValue = "false", matchIfMissing = true)
    DeferredSendRecoveryCodeEmail deferredSendRecoveryCodeEmail() {
        return new DeferredSendRecoveryCodeEmail();
    }

    @Bean
    AttachRecoveryEmail attachRecoveryEmail(
            SetAccountEmail setAccountEmail,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            SendRecoveryCodeEmail sendRecoveryCodeEmail,
            Clock clock) {
        return new AttachRecoveryEmail(setAccountEmail, emailRecoveryCodeStore, recoveryCodeHasher, sendRecoveryCodeEmail, clock);
    }

    @Bean
    ConfirmRecoveryEmail confirmRecoveryEmail(
            SetAccountEmail setAccountEmail,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            Clock clock) {
        return new ConfirmRecoveryEmail(setAccountEmail, emailRecoveryCodeStore, recoveryCodeHasher, clock);
    }

    @Bean
    DetachRecoveryEmail detachRecoveryEmail(
            SetAccountEmail setAccountEmail, EmailRecoveryCodeStore emailRecoveryCodeStore) {
        return new DetachRecoveryEmail(setAccountEmail, emailRecoveryCodeStore);
    }

    @Bean
    RequestEmailRecoveryCode requestEmailRecoveryCode(
            FindAccountByEmail findAccountByEmail,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            SendRecoveryCodeEmail sendRecoveryCodeEmail,
            Clock clock) {
        return new RequestEmailRecoveryCode(
                findAccountByEmail, emailRecoveryCodeStore, recoveryCodeHasher, sendRecoveryCodeEmail, clock);
    }

    @Bean
    ConfirmEmailRecovery confirmEmailRecovery(
            FindAccountByEmail findAccountByEmail,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            GetAccountDetails getAccountDetails,
            DeleteAccount deleteAccount,
            ProvisionedAccountRepository provisionedAccountRepository,
            RebindAccountCredential rebindAccountCredential,
            Clock clock) {
        return new ConfirmEmailRecovery(
                findAccountByEmail,
                emailRecoveryCodeStore,
                recoveryCodeHasher,
                getAccountDetails,
                deleteAccount,
                provisionedAccountRepository,
                rebindAccountCredential,
                clock);
    }

    // --- Story 7.4: consent capture -----------------------------------------------------------

    @Bean
    AccountConsentRepository accountConsentRepository(JdbcClient jdbcClient) {
        return new JdbcAccountConsentRepository(jdbcClient);
    }

    /**
     * {@code sgart.identity.consent.notice-version} (design §8 F2, D-E) is the single source of
     * the current notice version — a bump is a one-line deploy, never an app release. Shared by
     * both {@link RecordConsent} (what it stamps) and {@link GetConsentStatus} (what it reports).
     */
    @Bean
    RecordConsent recordConsent(
            AccountConsentRepository accountConsentRepository,
            Clock clock,
            @Value("${sgart.identity.consent.notice-version}") String currentNoticeVersion) {
        return new RecordConsent(accountConsentRepository, clock, currentNoticeVersion);
    }

    @Bean
    GetConsentStatus getConsentStatus(
            AccountConsentRepository accountConsentRepository,
            @Value("${sgart.identity.consent.notice-version}") String currentNoticeVersion) {
        return new GetConsentStatus(accountConsentRepository, currentNoticeVersion);
    }
}
