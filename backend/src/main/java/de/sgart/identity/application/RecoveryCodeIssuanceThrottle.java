package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;

/**
 * Bounds how often a recovery code may be issued for one account (Story 8.6): unthrottled
 * issuance lets an attacker spam a victim's inbox, and re-requesting a code resets the attempt
 * counter, which allows an unbounded brute force of the 6-digit code over time. The harm lands on
 * the <strong>target</strong> account the code is issued for, not the caller (attach is
 * self-service but recover is unauthenticated and the caller is free to retry), so the budget is
 * keyed by the target's pseudonymous {@link KeycloakUserId} — never the email address — and shared
 * across both issuing purposes (attach + recover) rather than tracked separately per purpose.
 */
public interface RecoveryCodeIssuanceThrottle {

    /**
     * @return {@code true} and records the issuance if the account is within its budget;
     *     {@code false}, with no side effect, if it is within the cooldown since its last issuance
     *     or has reached its rolling-window cap.
     */
    boolean tryIssue(KeycloakUserId targetAccount);
}
