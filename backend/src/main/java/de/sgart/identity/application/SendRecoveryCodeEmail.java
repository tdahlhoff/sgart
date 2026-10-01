package de.sgart.identity.application;

/**
 * Narrow, intention-revealing out-port, not a generic mail gateway (YAGNI). Both mails are plain
 * German text with no link and no household or nickname data. The real adapter sends over SMTP;
 * the {@code Deferred…} no-op default sends nothing, keeping every build that doesn't set
 * {@code sgart.identity.mail.enabled=true} free of any SMTP dependency.
 */
public interface SendRecoveryCodeEmail {

    /** Mails the code that confirms a recovery address a signed-in person is attaching to their account. */
    void sendAttachConfirmationCode(String address, String code);

    /** Mails the code that proves mailbox ownership to a person recovering an account on a new device. */
    void sendRecoveryCode(String address, String code);
}
