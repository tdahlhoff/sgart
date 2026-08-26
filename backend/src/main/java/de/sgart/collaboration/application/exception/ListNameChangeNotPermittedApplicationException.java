package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.ListNameChangeNotPermittedException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@code ListNameChangeNotPermittedException}
 * into a stable, client-localizable {@code list.nameChangeNotPermitted} code — a clean {@code 403}
 * (Story 2.1, AC3). Lives here, not in {@code collaboration.domain}, so the write-side error advice
 * in {@code adapter.in} can catch it without reaching into the domain layer, mirroring {@link
 * RenameNotPermittedApplicationException}.
 */
public final class ListNameChangeNotPermittedApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public ListNameChangeNotPermittedApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("list.nameChangeNotPermitted", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
