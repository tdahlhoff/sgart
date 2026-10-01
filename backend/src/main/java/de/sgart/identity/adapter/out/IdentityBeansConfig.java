package de.sgart.identity.adapter.out;

import de.sgart.identity.application.AttachRecoveryEmail;
import de.sgart.identity.application.ConfirmEmailRecovery;
import de.sgart.identity.application.ConfirmRecoveryEmail;
import de.sgart.identity.application.CreateAccount;
import de.sgart.identity.application.DeleteAccount;
import de.sgart.identity.application.AttachMailThrottle;
import de.sgart.identity.application.AttachRequestThrottle;
import de.sgart.identity.application.DetachRecoveryEmail;
import de.sgart.identity.application.FindHouseholdNames;
import de.sgart.identity.application.GetAccountDetails;
import de.sgart.identity.application.GetConsentStatus;
import de.sgart.identity.application.GetRecoveryEmailStatus;
import de.sgart.identity.application.PurgeExpiredRecoveryEmailState;
import de.sgart.identity.application.RecordConsent;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.identity.application.IssueMemberIdentity;
import de.sgart.identity.application.ProvisionAccount;
import de.sgart.identity.application.PruneDeviceToken;
import de.sgart.identity.application.RebindAccountCredential;
import de.sgart.identity.application.RecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailDigester;
import de.sgart.identity.application.RecoveryRequestThrottle;
import de.sgart.identity.application.RegisterDeviceToken;
import de.sgart.identity.application.ResolveRecoveryCandidates;
import de.sgart.identity.application.RequestEmailRecoveryCode;
import de.sgart.identity.application.ResolveHouseholdPushTargets;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.application.ResolveMembershipNicknames;
import de.sgart.identity.application.RetractMembership;
import de.sgart.identity.application.SendRecoveryCodeEmail;
import de.sgart.identity.application.SetMembershipNickname;
import de.sgart.identity.application.SweepNeverActivatedAccounts;
import de.sgart.identity.application.UnregisterDeviceToken;
import de.sgart.identity.domain.AccountConsentRepository;
import de.sgart.identity.domain.DeviceTokenRepository;
import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.identity.domain.MembershipNicknameRepository;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

/**
 * Wires the Identity ACL's durable mapping repository and its application-layer ports (Story 1.6:
 * the issue/write path and the mapping table, deferred from Story 1.4). Building the {@code
 * JdbcClient}-backed repository performs no I/O, so {@code contextLoads()} survives Postgres being
 * down.
 */
@Configuration
public class IdentityBeansConfig implements DisposableBean {

    private final List<BoundedDaemonExecutor> ownedExecutors = new CopyOnWriteArrayList<>();

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

    @Bean
    RetractMembership retractMembership(
            MemberMappingRepository memberMappingRepository, MembershipNicknameRepository membershipNicknameRepository) {
        return new RetractMembership(memberMappingRepository, membershipNicknameRepository);
    }

    // --- Story 8.3: per-household nickname -----------------------------------------------------

    @Bean
    MembershipNicknameRepository membershipNicknameRepository(JdbcClient jdbcClient) {
        return new JdbcMembershipNicknameRepository(jdbcClient);
    }

    @Bean
    SetMembershipNickname setMembershipNickname(
            MembershipNicknameRepository membershipNicknameRepository,
            ResolveMemberIdentity resolveMemberIdentity) {
        return new SetMembershipNickname(membershipNicknameRepository, resolveMemberIdentity);
    }

    @Bean
    ResolveMembershipNicknames resolveMembershipNicknames(MembershipNicknameRepository membershipNicknameRepository) {
        return new ResolveMembershipNicknames(membershipNicknameRepository);
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
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            Clock clock,
            @Value("${sgart.identity.provisioning.retention-days}") long retentionDays) {
        return new SweepNeverActivatedAccounts(
                provisionedAccountRepository,
                memberMappingRepository,
                deleteAccount,
                recoveryEmailBindingRepository,
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
     * The wired defaults — active whenever the Keycloak Admin adapter above is not, so the
     * suite/CI/local dev need no Keycloak Admin credentials.
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
    // No separate @Bean methods wrap keycloakAdminCreateAccount() for RebindAccountCredential/
    // GetAccountDetails: Spring already matches that single bean (declared as the concrete
    // KeycloakAdminCreateAccount type) against every interface it implements when another @Bean
    // method asks for one, exactly like CreateAccount/DeleteAccount above — an extra per-interface
    // wrapper method would register the *same* instance under additional bean names, and once
    // Spring resolves one by its actual runtime type it becomes an ambiguous extra candidate for
    // every other interface that type implements.


    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.keycloak-admin", name = "enabled", havingValue = "false", matchIfMissing = true)
    DeferredRebindAccountCredential deferredRebindAccountCredential() {
        return new DeferredRebindAccountCredential();
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
     * The real SMTP adapter — active only when {@code sgart.identity.mail.enabled=true}. Mails are
     * handed to a single daemon thread so a request returns at once, whatever the address. The
     * executor is deliberately not a bean: a user-defined {@code Executor} bean would switch off
     * Spring Boot's own task executor. Building the {@link JavaMailSender} performs no I/O; only
     * the first delivery reaches an SMTP server.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sgart.identity.mail", name = "enabled", havingValue = "true")
    SendRecoveryCodeEmail javaMailSenderRecoveryCodeEmail(
            JavaMailSender javaMailSender, @Value("${sgart.identity.mail.from}") String fromAddress) {
        return new AsynchronousSendRecoveryCodeEmail(
                new JavaMailSenderRecoveryCodeEmail(javaMailSender, fromAddress),
                startDaemonExecutor("recovery-email-sender"));
    }

    /** The wired default — no build needs an SMTP server. */
    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.mail", name = "enabled", havingValue = "false", matchIfMissing = true)
    DeferredSendRecoveryCodeEmail deferredSendRecoveryCodeEmail() {
        return new DeferredSendRecoveryCodeEmail();
    }

    // --- Recovery-email ownership: the address as a digest index ------------------------------

    @Bean
    RecoveryEmailBindingRepository recoveryEmailBindingRepository(
            JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcRecoveryEmailBindingRepository(jdbcClient, new TransactionTemplate(transactionManager));
    }

    @Bean
    RecoveryEmailDigester recoveryEmailDigester(
            @Value("${sgart.identity.email-recovery.address-pepper}") String pepper) {
        return new HmacSha256RecoveryEmailDigester(pepper);
    }

    /**
     * The three separate in-memory budgets (per attacher, per address mail, per address recovery),
     * keyed by account ids and digests only. One bean serves all three ports.
     */
    @Bean
    InMemoryRecoveryEmailThrottles recoveryEmailThrottles(Clock clock) {
        return new InMemoryRecoveryEmailThrottles(clock);
    }

    @Bean
    AttachRecoveryEmail attachRecoveryEmail(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            RecoveryEmailDigester recoveryEmailDigester,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            SendRecoveryCodeEmail sendRecoveryCodeEmail,
            AttachRequestThrottle attachRequestThrottle,
            AttachMailThrottle attachMailThrottle,
            Clock clock) {
        return new AttachRecoveryEmail(
                recoveryEmailBindingRepository,
                recoveryEmailDigester,
                emailRecoveryCodeStore,
                recoveryCodeHasher,
                sendRecoveryCodeEmail,
                attachRequestThrottle,
                attachMailThrottle,
                clock);
    }

    @Bean
    ConfirmRecoveryEmail confirmRecoveryEmail(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            Clock clock) {
        return new ConfirmRecoveryEmail(
                recoveryEmailBindingRepository, emailRecoveryCodeStore, recoveryCodeHasher, clock);
    }

    @Bean
    DetachRecoveryEmail detachRecoveryEmail(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            EmailRecoveryCodeStore emailRecoveryCodeStore) {
        return new DetachRecoveryEmail(recoveryEmailBindingRepository, emailRecoveryCodeStore);
    }

    @Bean
    GetRecoveryEmailStatus getRecoveryEmailStatus(RecoveryEmailBindingRepository recoveryEmailBindingRepository) {
        return new GetRecoveryEmailStatus(recoveryEmailBindingRepository);
    }

    @Bean
    PurgeExpiredRecoveryEmailState purgeExpiredRecoveryEmailState(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            Clock clock) {
        return new PurgeExpiredRecoveryEmailState(recoveryEmailBindingRepository, emailRecoveryCodeStore, clock);
    }

    /**
     * Recovery issuances run on a single bounded daemon thread so a request returns at once,
     * whatever the address. The executor is deliberately not a bean (see {@link
     * RecoveryIssuanceExecutor}).
     */
    @Bean
    RecoveryIssuanceExecutor recoveryIssuanceExecutor() {
        return new RecoveryIssuanceExecutor(startDaemonExecutor("recovery-code-issuer"));
    }

    private BoundedDaemonExecutor startDaemonExecutor(String threadName) {
        BoundedDaemonExecutor executor = new BoundedDaemonExecutor(threadName);
        ownedExecutors.add(executor);
        return executor;
    }

    /** Stops the executors this configuration started when the context closes. */
    @Override
    public void destroy() {
        ownedExecutors.forEach(BoundedDaemonExecutor::close);
    }

    @Bean
    RequestEmailRecoveryCode requestEmailRecoveryCode(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            SendRecoveryCodeEmail sendRecoveryCodeEmail,
            RecoveryEmailDigester recoveryEmailDigester,
            RecoveryRequestThrottle recoveryRequestThrottle,
            RecoveryIssuanceExecutor recoveryIssuanceExecutor,
            Clock clock) {
        return new RequestEmailRecoveryCode(
                recoveryEmailBindingRepository,
                emailRecoveryCodeStore,
                recoveryCodeHasher,
                sendRecoveryCodeEmail,
                recoveryEmailDigester,
                recoveryRequestThrottle,
                recoveryIssuanceExecutor.executor(),
                clock);
    }

    @Bean
    ResolveRecoveryCandidates resolveRecoveryCandidates(
            MemberMappingRepository memberMappingRepository,
            MembershipNicknameRepository membershipNicknameRepository,
            FindHouseholdNames findHouseholdNames) {
        return new ResolveRecoveryCandidates(
                memberMappingRepository, membershipNicknameRepository, findHouseholdNames);
    }

    @Bean
    ConfirmEmailRecovery confirmEmailRecovery(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            RecoveryEmailDigester recoveryEmailDigester,
            RecoveryRequestThrottle recoveryRequestThrottle,
            ResolveRecoveryCandidates resolveRecoveryCandidates,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            GetAccountDetails getAccountDetails,
            DeleteAccount deleteAccount,
            ProvisionedAccountRepository provisionedAccountRepository,
            RebindAccountCredential rebindAccountCredential,
            CreateAccount createAccount,
            Clock clock) {
        return new ConfirmEmailRecovery(
                recoveryEmailBindingRepository,
                recoveryEmailDigester,
                recoveryRequestThrottle,
                resolveRecoveryCandidates,
                emailRecoveryCodeStore,
                recoveryCodeHasher,
                getAccountDetails,
                deleteAccount,
                provisionedAccountRepository,
                rebindAccountCredential,
                createAccount,
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
