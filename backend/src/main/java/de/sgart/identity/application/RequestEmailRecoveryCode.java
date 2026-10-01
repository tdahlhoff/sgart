package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailDigest;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requests a recover-by-email code on a fresh device. Only the address is validated on the
 * request thread; the whole issuance (binding lookup, budget, code, mail) runs on the injected
 * executor. A mailbox with confirmed bindings and one without therefore cost the request thread
 * the same, and nothing the caller can observe (status, body, timing) depends on the address.
 *
 * <p>One code is issued per mailbox, however many accounts are bound to it. Pending bindings grant
 * nothing; an address without a confirmed binding, or over its recovery budget, is silently dropped.
 */
public final class RequestEmailRecoveryCode {

    private static final Logger log = LoggerFactory.getLogger(RequestEmailRecoveryCode.class);

    private final RecoveryEmailBindingRepository recoveryEmailBindingRepository;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final RecoveryCodeHasher recoveryCodeHasher;
    private final SendRecoveryCodeEmail sendRecoveryCodeEmail;
    private final RecoveryEmailDigester recoveryEmailDigester;
    private final RecoveryRequestThrottle recoveryRequestThrottle;
    private final Executor issuanceExecutor;
    private final Clock clock;

    public RequestEmailRecoveryCode(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            SendRecoveryCodeEmail sendRecoveryCodeEmail,
            RecoveryEmailDigester recoveryEmailDigester,
            RecoveryRequestThrottle recoveryRequestThrottle,
            Executor issuanceExecutor,
            Clock clock) {
        this.recoveryEmailBindingRepository = Objects.requireNonNull(
                recoveryEmailBindingRepository, "recoveryEmailBindingRepository must not be null");
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
        this.recoveryCodeHasher = Objects.requireNonNull(recoveryCodeHasher, "recoveryCodeHasher must not be null");
        this.sendRecoveryCodeEmail =
                Objects.requireNonNull(sendRecoveryCodeEmail, "sendRecoveryCodeEmail must not be null");
        this.recoveryEmailDigester =
                Objects.requireNonNull(recoveryEmailDigester, "recoveryEmailDigester must not be null");
        this.recoveryRequestThrottle =
                Objects.requireNonNull(recoveryRequestThrottle, "recoveryRequestThrottle must not be null");
        this.issuanceExecutor = Objects.requireNonNull(issuanceExecutor, "issuanceExecutor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** @throws InvalidRecoveryEmailException if {@code rawEmail} is missing or not a plausible address. */
    public void request(String rawEmail) {
        String address = RecoveryEmailValidation.validated(rawEmail);
        issuanceExecutor.execute(() -> issueCodeFor(address));
    }

    private void issueCodeFor(String address) {
        try {
            RecoveryEmailDigest digest = recoveryEmailDigester.digest(address);
            if (recoveryEmailBindingRepository.findConfirmedFor(digest).isEmpty()) {
                return;
            }
            if (!recoveryRequestThrottle.tryRequest(digest)) {
                return;
            }
            String code = RecoveryCode.generate();
            emailRecoveryCodeStore.store(
                    RecoveryCodeSubject.forAddress(digest),
                    RecoveryCodePurpose.RECOVER,
                    recoveryCodeHasher.hash(code),
                    RecoveryCode.expiresAt(clock),
                    clock.instant());
            sendRecoveryCodeEmail.sendRecoveryCode(address, code);
        } catch (RuntimeException issuanceFailure) {
            // Never the address: neither in the message nor in the exception chain's own text.
            log.error("Issuing a recovery code failed", issuanceFailure);
        }
    }
}
