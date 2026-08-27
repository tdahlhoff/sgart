package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.ShoppingList;

/**
 * Raised by {@link ShoppingList#updateItem} when the target {@code ItemId} is not held by the list
 * (Story 2.3, AC3) — a clean {@code 404} rather than updating a phantom item. Unlike {@link
 * ShoppingList#removeItem}, an update to an unknown item is not a convergent no-op: the caller
 * expected to change a specific item's fields, and there is nothing to converge to.
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}
 * (AD-1). The application layer translates it into the client-localizable {@code item.notFound}
 * code.
 */
public final class ItemNotFoundException extends RuntimeException {

    public ItemNotFoundException(String message) {
        super(message);
    }
}
