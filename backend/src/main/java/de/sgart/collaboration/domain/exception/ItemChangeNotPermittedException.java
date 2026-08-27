package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;

/**
 * Raised by {@link ShoppingList#addItem}/{@link ShoppingList#updateItem}/{@link
 * ShoppingList#removeItem} when the list is not {@link ListStatus#OPEN} (Story 2.3, AC5) — item
 * commands are permitted only while a list is {@code Open}; whether an {@code IN_TRIP} list
 * accepts them is an Epic-3 decision. Mirrors {@link ListNameChangeNotPermittedException}.
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}
 * (AD-1). The application layer translates it into the client-localizable {@code
 * item.changeNotPermitted} code (a {@code 403}).
 */
public final class ItemChangeNotPermittedException extends RuntimeException {

    public ItemChangeNotPermittedException(String message) {
        super(message);
    }
}
