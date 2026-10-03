package de.sgart.identity.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when the R1 rebind failed and the caller's throwaway account could not be given its
 * username back either. Until a retry succeeds the device cannot sign in as itself, so this is
 * reported separately from {@link RecoveryRebindFailedException}; the recovery code is kept and an
 * immediate retry heals it.
 */
public final class RecoveryThrowawayRestoreFailedException extends RuntimeException {

    private static final String ERROR_CODE = "account.recoveryRestoreFailed";

    private final ErrorDescriptor errorDescriptor;

    public RecoveryThrowawayRestoreFailedException(Throwable cause) {
        super("Recovery failed and the device account could not be restored; the recovery code is kept, retry", cause);
        this.errorDescriptor = ErrorDescriptor.of(ERROR_CODE, getMessage());
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
