package de.sgart.identity.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when an email passed to attach or recover-by-email is missing or not a plausible email
 * address (Story 7.3, AC1: "rejected fast", never a 500). Mirrors {@link
 * InvalidAccountProvisioningException}'s shape.
 */
public final class InvalidRecoveryEmailException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidRecoveryEmailException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
