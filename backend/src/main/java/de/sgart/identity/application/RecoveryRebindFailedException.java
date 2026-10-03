package de.sgart.identity.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when the R1 rebind fails (an infrastructure failure — Keycloak 5xx or network). The
 * throwaway account has its username back under its own id and the recovery code is kept, so the
 * person can simply retry; the response is a clean, retryable failure instead of a bricked device.
 */
public final class RecoveryRebindFailedException extends RuntimeException {

    private static final String ERROR_CODE = "account.recoveryRebindFailed";

    private final ErrorDescriptor errorDescriptor;

    public RecoveryRebindFailedException(Throwable cause) {
        super("Recovery could not be completed; the recovery code is kept, retry", cause);
        this.errorDescriptor = ErrorDescriptor.of(ERROR_CODE, getMessage());
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
