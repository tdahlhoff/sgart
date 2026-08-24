package de.sgart.collaboration.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@code RenameNotPermittedException} into a
 * stable, client-localizable {@code household.renameNotPermitted} code — a clean {@code 403}
 * (Story 1.7, AC4). Lives here, not in {@code collaboration.domain}, so the write-side error advice
 * in {@code adapter.in} can catch it without reaching into the domain layer (the same pattern
 * {@link InvalidHouseholdNameException} and {@code NotAMemberException} established).
 */
public final class RenameNotPermittedApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    RenameNotPermittedApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("household.renameNotPermitted", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
