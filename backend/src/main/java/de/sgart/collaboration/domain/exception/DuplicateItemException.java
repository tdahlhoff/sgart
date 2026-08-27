package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.ShoppingList;

/**
 * Raised by {@link ShoppingList#addItem}/{@link ShoppingList#updateItem} when the requested
 * (name, note) key duplicates an existing item's key on the same list (Story 2.3, AC2/AC3) —
 * comparison is trimmed and case-insensitive, mirroring {@link DuplicateStoreNameException}.
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}
 * (AD-1). The application layer translates it into the client-localizable {@code item.duplicate}
 * code (a {@code 409} Conflict).
 */
public final class DuplicateItemException extends RuntimeException {

    public DuplicateItemException(String message) {
        super(message);
    }
}
