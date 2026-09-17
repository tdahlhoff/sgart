package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Fast unit test (a stubbed {@link JavaMailSender}, no live SMTP server, CLAUDE.md §6) — proves
 * Story 7.3's real send adapter delivers to the right recipient with the code, and carries no
 * household data or name (minimal-content, design §3.1).
 */
class JavaMailSenderRecoveryCodeEmailTest {

    @Test
    void javaMailSenderRecoveryCodeEmail_sendsCodeToRecipient() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        JavaMailSenderRecoveryCodeEmail adapter =
                new JavaMailSenderRecoveryCodeEmail(javaMailSender, "no-reply@sgart.example");

        adapter.send("person@example.com", "042817");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("person@example.com");
        assertThat(sent.getFrom()).isEqualTo("no-reply@sgart.example");
        assertThat(sent.getText()).contains("042817");
        assertThat(sent.getText()).doesNotContain("Haushalt");
    }
}
