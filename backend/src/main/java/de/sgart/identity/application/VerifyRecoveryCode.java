package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Clock;
import java.util.Objects;

/**
 * Shared wrong/expired/exhausted verification (Story 7.3) — used by both {@link
 * ConfirmRecoveryEmail} and {@link ConfirmEmailRecovery} so the one security-sensitive rule set
 * (TTL, attempt cap, HMAC comparison) lives in exactly one place (DRY).
 */
final class VerifyRecoveryCode {

    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final RecoveryCodeHasher recoveryCodeHasher;
    private final Clock clock;

    VerifyRecoveryCode(EmailRecoveryCodeStore emailRecoveryCodeStore, RecoveryCodeHasher recoveryCodeHasher, Clock clock) {
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
        this.recoveryCodeHasher = Objects.requireNonNull(recoveryCodeHasher, "recoveryCodeHasher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @throws RecoveryCodeRejectedException if no code is stored, or the candidate is wrong,
     *     expired, or attempt-exhausted — a wrong guess increments the stored attempt count first.
     */
    void verify(KeycloakUserId subject, RecoveryCodePurpose purpose, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            // Fail fast on a missing/blank code (Story 7.3 review finding): without this guard a
            // null candidate reaches the hasher and throws an unmapped NPE, surfacing as an
            // AccountErrorAdvice-uncaught 500 — contra the endpoint's "never 500" contract.
            throw new RecoveryCodeRejectedException("code must not be blank");
        }
        var stored = emailRecoveryCodeStore
                .find(subject, purpose)
                .orElseThrow(() -> new RecoveryCodeRejectedException("no active code for this subject/purpose"));

        if (!RecoveryCode.matches(stored, candidate, recoveryCodeHasher, clock)) {
            emailRecoveryCodeStore.incrementAttempts(subject, purpose);
            throw new RecoveryCodeRejectedException("code is wrong, expired, or attempt-exhausted");
        }
    }
}
