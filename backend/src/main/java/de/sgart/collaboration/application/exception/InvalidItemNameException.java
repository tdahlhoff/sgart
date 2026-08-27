package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when a command rejects a caller-supplied item name — the application-layer translation of
 * the domain's own invariant failure into a stable, client-localizable {@code code} (Story 2.3,
 * AC1). Carries the specific code so a blank name ({@code item.nameRequired}) and an over-{@link
 * ItemName#MAX_LENGTH} name ({@code item.nameTooLong}) surface distinct copy. The note counterpart
 * is {@link InvalidItemNoteException} — each type is named for the field it guards (CLAUDE.md §2).
 * Lives here, not in {@code collaboration.domain}, mirroring {@link InvalidShoppingListNameException}.
 */
public final class InvalidItemNameException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidItemNameException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
