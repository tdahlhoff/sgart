package de.sgart.identity.adapter.out;

import de.sgart.identity.application.SendRecoveryCodeEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Story 7.3 stand-in for {@link SendRecoveryCodeEmail}: sends nothing, only logs at debug
 * (never the code itself — logs are not a secret-safe channel). Wired whenever {@code
 * sgart.identity.mail.enabled=false} (the default — tests, CI, local dev need no SMTP server),
 * mirroring {@code DeferredCreateAccount}.
 */
public final class DeferredSendRecoveryCodeEmail implements SendRecoveryCodeEmail {

    private static final Logger log = LoggerFactory.getLogger(DeferredSendRecoveryCodeEmail.class);

    @Override
    public void send(String email, String code) {
        log.debug("DeferredSendRecoveryCodeEmail: mail sending is disabled, not sending a recovery code");
    }
}
