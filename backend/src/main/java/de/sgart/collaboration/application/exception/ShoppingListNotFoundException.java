package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.RenameShoppingListHandler;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised by {@link RenameShoppingListHandler} when the target {@code listId} has no stream (never
 * created) — a clean {@code 404} rather than renaming a phantom aggregate. Also raised, defense-in-
 * depth, when a list's loaded {@code householdId} does not match the request path's household: a
 * list under a different household is treated the same as an unknown one, so the response never
 * confirms a list's existence under a household the caller did not ask about.
 */
public final class ShoppingListNotFoundException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public ShoppingListNotFoundException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("list.notFound", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
