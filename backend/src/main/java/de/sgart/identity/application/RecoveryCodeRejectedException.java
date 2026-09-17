package de.sgart.identity.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when a one-time recovery code is wrong, expired, or attempt-exhausted (Story 7.3, AC1/
 * AC2: "rejected fast", never a 500) — deliberately one generic failure code for every reason
 * (never distinguishing wrong-vs-expired-vs-exhausted in the response), so a caller learns nothing
 * that would help guess or enumerate (D-H's no-enumeration discipline, extended to code guessing).
 */
public final class RecoveryCodeRejectedException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public RecoveryCodeRejectedException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("account.recoveryCodeInvalid", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
