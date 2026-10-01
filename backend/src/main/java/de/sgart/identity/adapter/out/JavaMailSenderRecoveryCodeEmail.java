package de.sgart.identity.adapter.out;

import de.sgart.identity.application.SendRecoveryCodeEmail;
import java.util.Objects;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The real {@link SendRecoveryCodeEmail} adapter: plain-German, minimal-content SMTP mails with the
 * code, its lifetime, and an "ignore if not requested" line. No link, no household data, and no
 * name: whoever triggers a mail chooses nothing that could be used to phish the reader. All
 * {@code jakarta.mail}/Spring-mail types stay contained here (AD-1/AD-2). Active only when {@code
 * sgart.identity.mail.enabled=true}; the {@link DeferredSendRecoveryCodeEmail} no-op is wired otherwise.
 */
public final class JavaMailSenderRecoveryCodeEmail implements SendRecoveryCodeEmail {

    private static final String LIFETIME_LINE = "Der Code ist 15 Minuten gültig.";
    private static final String IGNORE_LINE =
            "Du hast das nicht bei SGART angefordert? Dann ignoriere diese E-Mail einfach.";

    private final JavaMailSender javaMailSender;
    private final String fromAddress;

    public JavaMailSenderRecoveryCodeEmail(JavaMailSender javaMailSender, String fromAddress) {
        this.javaMailSender = Objects.requireNonNull(javaMailSender, "javaMailSender must not be null");
        this.fromAddress = Objects.requireNonNull(fromAddress, "fromAddress must not be null");
    }

    @Override
    public void sendAttachConfirmationCode(String address, String code) {
        send(
                address,
                "Dein SGART-Bestätigungscode",
                "Dein Bestätigungscode für die Wiederherstellungs-E-Mail lautet: " + code + "\n\n"
                        + LIFETIME_LINE + "\n\n" + IGNORE_LINE);
    }

    @Override
    public void sendRecoveryCode(String address, String code) {
        send(
                address,
                "Dein SGART-Wiederherstellungscode",
                "Dein SGART-Wiederherstellungscode lautet: " + code + "\n\n" + LIFETIME_LINE + "\n\n" + IGNORE_LINE);
    }

    private void send(String address, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(address);
        message.setSubject(subject);
        message.setText(text);
        javaMailSender.send(message);
    }
}
