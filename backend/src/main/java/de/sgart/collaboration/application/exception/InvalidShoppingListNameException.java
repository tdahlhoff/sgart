package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.CreateShoppingListHandler;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when {@link CreateShoppingListHandler}/{@code RenameShoppingListHandler} reject a
 * caller-supplied list name — the application-layer translation of the domain's own invariant
 * failure into a stable, client-localizable {@code code} (Story 2.1, AC1/AC3). Carries the specific
 * code so a blank name ({@code list.nameRequired}) and an over-{@link
 * ShoppingListName#MAX_LENGTH} name ({@code list.nameTooLong}) surface distinct copy. Lives here,
 * not in {@code collaboration.domain}, mirroring {@link InvalidHouseholdNameException}.
 */
public final class InvalidShoppingListNameException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidShoppingListNameException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
