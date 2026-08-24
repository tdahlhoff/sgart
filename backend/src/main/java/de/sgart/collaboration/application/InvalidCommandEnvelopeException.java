package de.sgart.collaboration.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when a write command's client envelope is malformed — a missing ({@code
 * command.commandIdRequired}) or non-UUID ({@code command.commandIdInvalid}) {@code commandId}, or a
 * missing ({@code command.householdIdRequired}) or non-UUID ({@code command.householdIdInvalid})
 * target {@code householdId} taken from the request path.
 * Turns a fail-fast validation failure into a clean, client-localizable {@code 400} instead of an
 * opaque {@code 500} from a raw parse exception. Lives in {@code collaboration.application} so the
 * write-side error advice in {@code adapter.in} can catch it without reaching into the domain
 * (mirroring {@link InvalidHouseholdNameException}).
 */
public final class InvalidCommandEnvelopeException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    InvalidCommandEnvelopeException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
