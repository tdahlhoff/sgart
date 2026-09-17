package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.identity.domain.ProvisionedAccount;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The GDPR storage-limitation control for silent account provisioning (Story 7.1, AC5): deletes a
 * {@code ProvisionedAccount} shell — Keycloak account and row alike — once it has sat unactivated
 * past the retention TTL. "Activated" is <strong>derived</strong>, never stored (DRY, mirrors
 * {@link de.sgart.identity.domain.ProvisionedAccount}'s own doc): a shell is activated the moment a
 * {@link MemberMappingRepository} row exists for its {@link KeycloakUserId}, <strong>or</strong>
 * (Story 7.3, design §7, D-G) the account carries a <em>confirmed</em> Keycloak email — checked
 * live via {@link GetAccountDetails}, never a stored SGART bool. An activated account is kept
 * regardless of age; an unconfirmed-attach shell is still swept past the TTL.
 *
 * <p>Triggered by a scheduled adapter ({@code adapter.in.ScheduledAccountRetentionSweep}, F4); this
 * service is called directly by tests so the assertion never waits on a cron (Testing standards,
 * CLAUDE.md §6).
 */
public final class SweepNeverActivatedAccounts {

    private static final Logger log = LoggerFactory.getLogger(SweepNeverActivatedAccounts.class);

    private final ProvisionedAccountRepository provisionedAccountRepository;
    private final MemberMappingRepository memberMappingRepository;
    private final DeleteAccount deleteAccount;
    private final GetAccountDetails getAccountDetails;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final Clock clock;
    private final Duration retentionPeriod;

    public SweepNeverActivatedAccounts(
            ProvisionedAccountRepository provisionedAccountRepository,
            MemberMappingRepository memberMappingRepository,
            DeleteAccount deleteAccount,
            GetAccountDetails getAccountDetails,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            Clock clock,
            Duration retentionPeriod) {
        this.provisionedAccountRepository =
                Objects.requireNonNull(provisionedAccountRepository, "provisionedAccountRepository must not be null");
        this.memberMappingRepository =
                Objects.requireNonNull(memberMappingRepository, "memberMappingRepository must not be null");
        this.deleteAccount = Objects.requireNonNull(deleteAccount, "deleteAccount must not be null");
        this.getAccountDetails = Objects.requireNonNull(getAccountDetails, "getAccountDetails must not be null");
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.retentionPeriod = Objects.requireNonNull(retentionPeriod, "retentionPeriod must not be null");
    }

    /**
     * Deletes every never-activated shell whose {@code provisionedAt} is past the retention TTL.
     * One account's delete failure is logged and skipped rather than aborting the run — otherwise
     * a single bad account would leave every other overdue account unswept until the next
     * scheduled run.
     */
    public void sweep() {
        Instant threshold = clock.instant().minus(retentionPeriod);
        for (ProvisionedAccount account : provisionedAccountRepository.findProvisionedBefore(threshold)) {
            if (!isNeverActivated(account.keycloakUserId())) {
                continue;
            }
            try {
                deleteAccount.delete(account.keycloakUserId());
                provisionedAccountRepository.delete(account.keycloakUserId());
                emailRecoveryCodeStore.deleteAll(account.keycloakUserId());
            } catch (RuntimeException deletionFailed) {
                log.error(
                        "SweepNeverActivatedAccounts: failed to delete never-activated account {} — "
                                + "skipping, will retry on the next scheduled sweep",
                        account.keycloakUserId(),
                        deletionFailed);
            }
        }
    }

    private boolean isNeverActivated(KeycloakUserId keycloakUserId) {
        if (!memberMappingRepository.householdIdsFor(keycloakUserId).isEmpty()) {
            return false;
        }
        return !hasConfirmedEmail(keycloakUserId);
    }

    private boolean hasConfirmedEmail(KeycloakUserId keycloakUserId) {
        return getAccountDetails.findById(keycloakUserId).map(AccountDetails::emailVerified).orElse(false);
    }
}
