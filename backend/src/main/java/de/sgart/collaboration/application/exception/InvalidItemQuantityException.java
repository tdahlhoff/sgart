package de.sgart.collaboration.application.exception;

import de.sgart.shared.ErrorDescriptor;
import de.sgart.shared.Quantity;

/**
 * Raised when a command rejects a caller-supplied item quantity — the application-layer
 * translation of a malformed/invalid {@link Quantity} into a stable, client-localizable {@code
 * code} (Story 2.3, AC1, AD-9). Carries the specific code so a missing amount/unit ({@code
 * item.quantityRequired}) and a malformed/non-positive amount or unrecognized unit ({@code
 * item.quantityInvalid}) surface distinct copy. Lives here, not in {@code collaboration.domain},
 * mirroring {@link InvalidShoppingListNameException}.
 */
public final class InvalidItemQuantityException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidItemQuantityException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
