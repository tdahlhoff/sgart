package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.GovernanceNotPermittedException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@link GovernanceNotPermittedException} into a
 * stable, client-localizable {@code governance.notPermitted} code — a clean {@code 403 Forbidden}
 * (Story 4.3, AC2, AC6). Lives here, not in {@code collaboration.domain}, so the write-side error
 * advice in {@code adapter.in} can catch it without reaching into the domain layer (AD-1).
 */
public final class GovernanceNotPermittedApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public GovernanceNotPermittedApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("governance.notPermitted", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
