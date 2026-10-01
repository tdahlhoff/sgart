package de.sgart.identity.adapter.in;

import de.sgart.identity.application.PurgeExpiredRecoveryEmailState;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The driving (inbound) adapter for {@link PurgeExpiredRecoveryEmailState}: a scheduled trigger.
 * The cron expression is configuration ({@code sgart.identity.email-recovery.purge-cron}), so
 * tests call {@link PurgeExpiredRecoveryEmailState#purge()} directly instead of waiting on it.
 */
@Component
class ScheduledRecoveryEmailPurge {

    private final PurgeExpiredRecoveryEmailState purgeExpiredRecoveryEmailState;

    ScheduledRecoveryEmailPurge(PurgeExpiredRecoveryEmailState purgeExpiredRecoveryEmailState) {
        this.purgeExpiredRecoveryEmailState = Objects.requireNonNull(
                purgeExpiredRecoveryEmailState, "purgeExpiredRecoveryEmailState must not be null");
    }

    @Scheduled(cron = "${sgart.identity.email-recovery.purge-cron}")
    void run() {
        purgeExpiredRecoveryEmailState.purge();
    }
}
