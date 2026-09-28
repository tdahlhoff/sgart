package de.sgart.identity.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when {@link AttachRecoveryEmail} would issue a recovery code for an account that is
 * currently over its {@link RecoveryCodeIssuanceThrottle} budget (Story 8.6). {@link
 * RequestEmailRecoveryCode} deliberately does <strong>not</strong> throw this — its {@code 202}
 * stays constant regardless (no enumeration, D-H) — so this exception is attach-only.
 */
public final class RecoveryCodeRateLimitedException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public RecoveryCodeRateLimitedException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("account.recoveryCodeRateLimited", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
