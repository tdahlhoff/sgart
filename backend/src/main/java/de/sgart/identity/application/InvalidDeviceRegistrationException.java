package de.sgart.identity.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when a device-registration request's envelope is malformed — a missing/blank {@code
 * device.tokenRequired} token, a missing/blank {@code device.platformRequired} platform, or an
 * unrecognized {@code device.platformInvalid} platform value (Story 4.5, AC5). Mirrors {@code
 * InvalidCommandEnvelopeException}'s (collaboration.application) shape so {@code adapter.in} maps
 * it to a clean, client-localizable {@code 400} instead of an opaque {@code 500}.
 */
public final class InvalidDeviceRegistrationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidDeviceRegistrationException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
