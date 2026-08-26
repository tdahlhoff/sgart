package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.ListStatus;
import de.sgart.collaboration.domain.ShoppingList;

/**
 * Raised by {@link ShoppingList#rename} when the list is not {@link ListStatus#OPEN} (Story 2.1,
 * AC3) — only an {@code OPEN} (and, from Epic 3, {@code IN_TRIP}) list may be renamed; a {@code
 * DONE} list rejects it.
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}: the
 * domain stays free of any outward-facing transport concern (AD-1), exactly as {@link
 * RenameNotPermittedException} does for {@code Household}. The application layer translates it into
 * the client-localizable {@code list.nameChangeNotPermitted} code.
 */
public final class ListNameChangeNotPermittedException extends RuntimeException {

    public ListNameChangeNotPermittedException(String message) {
        super(message);
    }
}
