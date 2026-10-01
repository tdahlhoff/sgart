package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Fast unit test (a stubbed {@link JavaMailSender}, no live SMTP server, CLAUDE.md §6): the real
 * send adapter delivers to the right recipient with the code, and carries no link, household data,
 * or name.
 */
class JavaMailSenderRecoveryCodeEmailTest {

    private final JavaMailSender javaMailSender = mock(JavaMailSender.class);
    private final JavaMailSenderRecoveryCodeEmail adapter =
            new JavaMailSenderRecoveryCodeEmail(javaMailSender, "no-reply@sgart.example");

    @Test
    void attachConfirmationMail_containsTheCodeAndTheLifetimeButNoLink() {
        adapter.sendAttachConfirmationCode("person@example.test", "042817");

        SimpleMailMessage sent = capturedMail();
        assertThat(sent.getTo()).containsExactly("person@example.test");
        assertThat(sent.getFrom()).isEqualTo("no-reply@sgart.example");
        assertThat(sent.getText()).contains("042817").contains("15 Minuten").contains("ignoriere diese E-Mail");
        assertThat(sent.getText()).doesNotContain("http");
    }

    @Test
    void recoveryMail_containsNoHouseholdNameNicknameOrLink() {
        adapter.sendRecoveryCode("person@example.test", "042817");

        SimpleMailMessage sent = capturedMail();
        assertThat(sent.getTo()).containsExactly("person@example.test");
        assertThat(sent.getText()).contains("042817").contains("15 Minuten");
        assertThat(sent.getText()).doesNotContain("http").doesNotContain("Haushalt").doesNotContain("Spitzname");
    }

    private SimpleMailMessage capturedMail() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        return captor.getValue();
    }
}
