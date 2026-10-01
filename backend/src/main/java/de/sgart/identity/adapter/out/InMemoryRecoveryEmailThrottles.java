package de.sgart.identity.adapter.out;

import de.sgart.identity.application.AttachMailThrottle;
import de.sgart.identity.application.AttachRequestThrottle;
import de.sgart.identity.application.RecoveryRequestThrottle;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailDigest;
import java.time.Clock;
import java.time.Duration;

/**
 * The three separate recovery-email budgets over one generic in-memory window. Each budget keeps
 * its own history, so exhausting one never affects another. Hosted in a single class only because
 * the three share their mechanics and lifecycle; the application sees three distinct ports.
 */
public final class InMemoryRecoveryEmailThrottles
        implements AttachRequestThrottle, AttachMailThrottle, RecoveryRequestThrottle {

    private static final Duration DAY = Duration.ofHours(24);

    static final InMemorySlidingWindowThrottle.Policy ATTACH_REQUEST_POLICY =
            new InMemorySlidingWindowThrottle.Policy(Duration.ofSeconds(60), DAY, 5);
    static final InMemorySlidingWindowThrottle.Policy ATTACH_MAIL_POLICY =
            new InMemorySlidingWindowThrottle.Policy(Duration.ZERO, DAY, 3);
    static final InMemorySlidingWindowThrottle.Policy RECOVERY_REQUEST_POLICY =
            new InMemorySlidingWindowThrottle.Policy(Duration.ofSeconds(60), DAY, 10);

    private final InMemorySlidingWindowThrottle<KeycloakUserId> attachRequests;
    private final InMemorySlidingWindowThrottle<RecoveryEmailDigest> attachMails;
    private final InMemorySlidingWindowThrottle<RecoveryEmailDigest> recoveryRequests;

    public InMemoryRecoveryEmailThrottles(Clock clock) {
        this.attachRequests = new InMemorySlidingWindowThrottle<>(clock, ATTACH_REQUEST_POLICY);
        this.attachMails = new InMemorySlidingWindowThrottle<>(clock, ATTACH_MAIL_POLICY);
        this.recoveryRequests = new InMemorySlidingWindowThrottle<>(clock, RECOVERY_REQUEST_POLICY);
    }

    @Override
    public boolean tryAttach(KeycloakUserId caller) {
        return attachRequests.tryAcquire(caller);
    }

    @Override
    public boolean tryMail(RecoveryEmailDigest digest) {
        return attachMails.tryAcquire(digest);
    }

    @Override
    public boolean tryRequest(RecoveryEmailDigest digest) {
        return recoveryRequests.tryAcquire(digest);
    }

    @Override
    public void reset(RecoveryEmailDigest digest) {
        recoveryRequests.reset(digest);
    }

    /** Test helper — resets every budget between test methods that share one Spring context. */
    public void clear() {
        attachRequests.clear();
        attachMails.clear();
        recoveryRequests.clear();
    }
}
