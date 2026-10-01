package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.sgart.identity.application.SendRecoveryCodeEmail;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class AsynchronousSendRecoveryCodeEmailTest {

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
    void send_whenTheDeliveryFails_doesNotPropagateTheFailureToTheExecutor() {
        SendRecoveryCodeEmail failingDelegate = new SendRecoveryCodeEmail() {
            @Override
            public void sendAttachConfirmationCode(String address, String code) {
                throw new IllegalStateException("smtp down for " + address);
            }

            @Override
            public void sendRecoveryCode(String address, String code) {
                throw new IllegalStateException("smtp down for " + address);
            }
        };
        new AsynchronousSendRecoveryCodeEmail(failingDelegate, capturingExecutor)
                .sendRecoveryCode("person@example.test", "042817");

        assertThatCode(() -> queuedTasks.forEach(Runnable::run)).doesNotThrowAnyException();
    }
}
