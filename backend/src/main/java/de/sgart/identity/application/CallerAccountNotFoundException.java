package de.sgart.identity.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when the account behind the caller's valid JWT no longer exists (for example a throwaway
 * deleted by a half-finished recovery). Surfaces as {@code 401 auth.unauthorized}, which the app
 * answers by re-signing-in silently against the account's current credential and retrying once.
 */
public final class CallerAccountNotFoundException extends RuntimeException {

    private static final String ERROR_CODE = "auth.unauthorized";

    private final ErrorDescriptor errorDescriptor;

    public CallerAccountNotFoundException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(ERROR_CODE, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
