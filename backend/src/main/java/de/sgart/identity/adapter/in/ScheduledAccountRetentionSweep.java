package de.sgart.identity.adapter.in;

import de.sgart.identity.application.SweepNeverActivatedAccounts;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The driving (inbound) adapter for {@link SweepNeverActivatedAccounts} (Story 7.1, AC5): a
 * scheduled trigger rather than an HTTP request, but still a "drives the application" adapter —
 * the timer is the only transport-specific detail here (AD-1). The cron expression is
 * configuration ({@code sgart.identity.provisioning.sweep-cron}), so tests never wait on it —
 * they call {@link SweepNeverActivatedAccounts#sweep()} directly (Testing standards, CLAUDE.md §6).
 * Requires {@code @EnableScheduling} on {@code SgartApplication} (F4).
 */
@Component
class ScheduledAccountRetentionSweep {

    private final SweepNeverActivatedAccounts sweepNeverActivatedAccounts;

    ScheduledAccountRetentionSweep(SweepNeverActivatedAccounts sweepNeverActivatedAccounts) {
        this.sweepNeverActivatedAccounts =
                Objects.requireNonNull(sweepNeverActivatedAccounts, "sweepNeverActivatedAccounts must not be null");
    }

    @Scheduled(cron = "${sgart.identity.provisioning.sweep-cron:0 0 3 * * *}")
    void run() {
        sweepNeverActivatedAccounts.sweep();
    }
}
