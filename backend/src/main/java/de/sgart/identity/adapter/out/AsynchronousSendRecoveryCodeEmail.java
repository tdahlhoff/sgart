package de.sgart.identity.adapter.out;

import de.sgart.identity.application.SendRecoveryCodeEmail;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator that hands each mail to an {@link Executor} and returns at once. The request thread
 * then takes about the same time whether or not a mail is really sent, so the response time does
 * not reveal anything about an address. A delivery failure is logged without the address or code
 * (the request has long been answered, and logs are not a secret-safe channel).
 */
public final class AsynchronousSendRecoveryCodeEmail implements SendRecoveryCodeEmail {

    private static final Logger log = LoggerFactory.getLogger(AsynchronousSendRecoveryCodeEmail.class);

    private final SendRecoveryCodeEmail delegate;
    private final Executor executor;

    public AsynchronousSendRecoveryCodeEmail(SendRecoveryCodeEmail delegate, Executor executor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public void sendAttachConfirmationCode(String address, String code) {
        executor.execute(() -> deliver("attach confirmation", () -> delegate.sendAttachConfirmationCode(address, code)));
    }

    @Override
    public void sendRecoveryCode(String address, String code) {
        executor.execute(() -> deliver("recovery", () -> delegate.sendRecoveryCode(address, code)));
    }

    private static void deliver(String mailKind, Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException deliveryFailure) {
            // Only the failure type: a mail exception's message or stack may quote the recipient.
            log.error("Failed to deliver a {} code mail: {}", mailKind, deliveryFailure.getClass().getName());
        }
    }
}
