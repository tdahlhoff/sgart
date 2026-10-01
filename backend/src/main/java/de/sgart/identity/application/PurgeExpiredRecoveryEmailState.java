package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The storage-limitation control for transient recovery-email state: deletes pending bindings
 * older than the code lifetime (they can no longer be confirmed) and code rows that have expired.
 * Confirmed bindings are never touched. The two cleanups are independent, so one failing is logged
 * and the other still runs.
 */
public final class PurgeExpiredRecoveryEmailState {

    private static final Logger log = LoggerFactory.getLogger(PurgeExpiredRecoveryEmailState.class);

    private final RecoveryEmailBindingRepository recoveryEmailBindingRepository;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final Clock clock;

    public PurgeExpiredRecoveryEmailState(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            Clock clock) {
        this.recoveryEmailBindingRepository = Objects.requireNonNull(
                recoveryEmailBindingRepository, "recoveryEmailBindingRepository must not be null");
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void purge() {
        Instant now = clock.instant();
        runIndependently(
                "pending recovery email bindings",
                () -> recoveryEmailBindingRepository.deletePendingCreatedBefore(now.minus(RecoveryCode.TTL)));
        runIndependently("expired recovery codes", () -> emailRecoveryCodeStore.deleteExpiredBefore(now));
    }

    private static void runIndependently(String description, Runnable purgeStep) {
        try {
            purgeStep.run();
        } catch (RuntimeException purgeFailure) {
            log.error("PurgeExpiredRecoveryEmailState: failed to purge {}; continuing", description, purgeFailure);
        }
    }
}
