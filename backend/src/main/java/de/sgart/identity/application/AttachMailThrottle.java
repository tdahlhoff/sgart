package de.sgart.identity.application;

import de.sgart.identity.domain.RecoveryEmailDigest;

/**
 * Per-address budget for attach confirmation mails, across all callers: it stops anyone from
 * flooding an inbox through throwaway accounts. Exhaustion is silent (the response stays
 * identical), so it must never become a signal about the address.
 */
public interface AttachMailThrottle {

    /** @return {@code true} and records the mail if within budget; {@code false}, without side effect, otherwise. */
    boolean tryMail(RecoveryEmailDigest digest);
}
