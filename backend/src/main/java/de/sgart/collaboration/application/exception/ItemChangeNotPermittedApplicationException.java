package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.ItemChangeNotPermittedException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@link ItemChangeNotPermittedException} into a
 * stable, client-localizable {@code item.changeNotPermitted} code — a clean {@code 403} (Story 2.3,
 * AC5). Lives here, not in {@code collaboration.domain}, mirroring {@link
 * ListNameChangeNotPermittedApplicationException}.
 */
public final class ItemChangeNotPermittedApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public ItemChangeNotPermittedApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("item.changeNotPermitted", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
