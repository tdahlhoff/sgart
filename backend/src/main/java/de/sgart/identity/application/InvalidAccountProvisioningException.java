package de.sgart.identity.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when a {@code POST /api/v1/accounts} request's envelope is malformed — a missing/blank or
 * non-base64url {@code account.publicKeyInvalid} public key that does not decode to a 32-byte
 * Ed25519 key, or a missing/unrecognized {@code account.platformInvalid} platform value (Story
 * 7.1, AC3: "never a 500"). Mirrors {@link InvalidDeviceRegistrationException}'s shape so {@code
 * adapter.in} maps it to a clean, client-localizable {@code 400}.
 */
public final class InvalidAccountProvisioningException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidAccountProvisioningException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
