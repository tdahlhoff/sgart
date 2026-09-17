package de.sgart.identity.adapter.out;

import de.sgart.identity.application.SendRecoveryCodeEmail;
import java.util.Objects;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The real Story 7.3 {@link SendRecoveryCodeEmail} adapter (design §3.1) — plain-German, minimal-
 * content SMTP send: the code, the TTL, and an "ignore if not requested" line, no household data
 * and no name. All {@code jakarta.mail}/Spring-mail types stay contained here (AD-1/AD-2). Active
 * only when {@code sgart.identity.mail.enabled=true}; the {@link DeferredSendRecoveryCodeEmail}
 * no-op is wired otherwise.
 */
public final class JavaMailSenderRecoveryCodeEmail implements SendRecoveryCodeEmail {

    private final JavaMailSender javaMailSender;
    private final String fromAddress;

    public JavaMailSenderRecoveryCodeEmail(JavaMailSender javaMailSender, String fromAddress) {
        this.javaMailSender = Objects.requireNonNull(javaMailSender, "javaMailSender must not be null");
        this.fromAddress = Objects.requireNonNull(fromAddress, "fromAddress must not be null");
    }

    @Override
    public void send(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("Dein SGART-Bestätigungscode");
        message.setText(
                "Dein Bestätigungscode lautet: " + code + "\n\n"
                        + "Der Code ist 15 Minuten gültig.\n\n"
                        + "Du hast das nicht bei SGART angefordert? Dann ignoriere diese E-Mail einfach.");
        javaMailSender.send(message);
    }
}
