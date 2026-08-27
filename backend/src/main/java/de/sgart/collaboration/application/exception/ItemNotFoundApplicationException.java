package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.exception.ItemNotFoundException;
import de.sgart.shared.ErrorDescriptor;

/**
 * The application-layer translation of the domain's {@link ItemNotFoundException} into the stable,
 * client-localizable {@code item.notFound} code — a {@code 404} (Story 2.3, AC3). Lives here, not
 * in {@code collaboration.domain}, mirroring {@link ShoppingListNotFoundException}.
 */
public final class ItemNotFoundApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public ItemNotFoundApplicationException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("item.notFound", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
