package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.ItemNote;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when a command rejects a caller-supplied item note — the application-layer translation of
 * the domain's own invariant failure into a stable, client-localizable {@code code} (Story 2.3,
 * AC1). Carries the specific code so an over-{@link ItemNote#MAX_LENGTH} note ({@code
 * item.noteTooLong}) surfaces its own copy. Distinct from {@link InvalidItemNameException} so each
 * type is named for the field it guards (CLAUDE.md §2 intention-revealing names). Lives here, not
 * in {@code collaboration.domain}, mirroring {@link InvalidShoppingListNameException}.
 */
public final class InvalidItemNoteException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidItemNoteException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
