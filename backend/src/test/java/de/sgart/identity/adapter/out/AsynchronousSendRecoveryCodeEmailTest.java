package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.sgart.identity.CapturedLogs;
import de.sgart.identity.application.SendRecoveryCodeEmail;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class AsynchronousSendRecoveryCodeEmailTest {

    private static final String ADDRESS = "person@example.test";
    private static final String CODE = "042817";

    private final List<Runnable> queuedTasks = new ArrayList<>();
    private final Executor capturingExecutor = queuedTasks::add;
    private final List<String> deliveries = new ArrayList<>();
    private final SendRecoveryCodeEmail recordingDelegate = new SendRecoveryCodeEmail() {
        @Override
        public void sendAttachConfirmationCode(String address, String code) {
            deliveries.add("attach:" + address + ":" + code);
        }

        @Override
        public void sendRecoveryCode(String address, String code) {
            deliveries.add("recovery:" + address + ":" + code);
        }
    };
    private final AsynchronousSendRecoveryCodeEmail asynchronousEmail =
            new AsynchronousSendRecoveryCodeEmail(recordingDelegate, capturingExecutor);

    @Test
    void send_returnsBeforeTheMailIsDelivered() {
        asynchronousEmail.sendAttachConfirmationCode("person@example.test", "042817");
        asynchronousEmail.sendRecoveryCode("person@example.test", "123456");

        assertThat(deliveries).isEmpty();

        queuedTasks.forEach(Runnable::run);

        assertThat(deliveries)
                .containsExactly("attach:person@example.test:042817", "recovery:person@example.test:123456");
    }

    @Test
    void sendRecoveryCode_whenTheDeliveryFails_doesNotPropagateTheFailureToTheExecutor() {
        new AsynchronousSendRecoveryCodeEmail(new LeakyFailingDelegate(), capturingExecutor)
                .sendRecoveryCode(ADDRESS, CODE);

        assertThatCode(() -> queuedTasks.forEach(Runnable::run)).doesNotThrowAnyException();
    }

    @Test
    void sendAttachConfirmationCode_whenTheDeliveryFails_doesNotPropagateTheFailureToTheExecutor() {
        new AsynchronousSendRecoveryCodeEmail(new LeakyFailingDelegate(), capturingExecutor)
                .sendAttachConfirmationCode(ADDRESS, CODE);

        assertThatCode(() -> queuedTasks.forEach(Runnable::run)).doesNotThrowAnyException();
    }

    @Test
    void sendRecoveryCode_whenTheDeliveryFails_logsNeitherTheAddressNorTheCode() {
        new AsynchronousSendRecoveryCodeEmail(new LeakyFailingDelegate(), capturingExecutor)
                .sendRecoveryCode(ADDRESS, CODE);

        assertDeliveryFailureLogLeaksNothing();
    }

    @Test
    void sendAttachConfirmationCode_whenTheDeliveryFails_logsNeitherTheAddressNorTheCode() {
        new AsynchronousSendRecoveryCodeEmail(new LeakyFailingDelegate(), capturingExecutor)
                .sendAttachConfirmationCode(ADDRESS, CODE);

        assertDeliveryFailureLogLeaksNothing();
    }

    private void assertDeliveryFailureLogLeaksNothing() {
        try (CapturedLogs logs = CapturedLogs.ofLoggerOf(AsynchronousSendRecoveryCodeEmail.class)) {
            queuedTasks.forEach(Runnable::run);

            assertThat(logs.hasLoggedAnything()).isTrue();
            assertThat(logs.allOutput()).doesNotContain(ADDRESS).doesNotContain(CODE);
        }
    }

    /** Fails like a mail server whose error message quotes both the recipient and the code. */
    private static final class LeakyFailingDelegate implements SendRecoveryCodeEmail {
        @Override
        public void sendAttachConfirmationCode(String address, String code) {
            throw new IllegalStateException("smtp down for " + address + " with code " + code);
        }

        @Override
        public void sendRecoveryCode(String address, String code) {
            throw new IllegalStateException("smtp down for " + address + " with code " + code);
        }
    }
}
