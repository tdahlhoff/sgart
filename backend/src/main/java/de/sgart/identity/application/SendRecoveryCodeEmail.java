package de.sgart.identity.application;

/**
 * Narrow, intention-revealing out-port (Story 7.3, design §3.1) — not a generic mail gateway
 * (YAGNI). The real adapter ({@code JavaMailSenderRecoveryCodeEmail}) sends the plain-German code
 * + TTL email over SMTP; the {@code Deferred…} no-op default sends nothing, keeping every build
 * that doesn't set {@code sgart.identity.mail.enabled=true} free of any SMTP dependency.
 */
public interface SendRecoveryCodeEmail {

    void send(String email, String code);
}
