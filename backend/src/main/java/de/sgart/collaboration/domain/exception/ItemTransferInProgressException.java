package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.ShoppingList;

/**
 * Raised when a member command targets an item that is currently reserved by a pending transfer
 * (Story 3.6, AC4) — the fail-fast lock: while an item is reserved, any <em>other</em> mutation
 * (edit, remove, move/postpone to a <em>different</em> target, check-off, uncheck, discard,
 * reroute, assign) is rejected rather than racing the in-flight saga. A retry of the <em>same</em>
 * transfer (same target) is instead a convergent no-op — see {@link ShoppingList#moveItem}/{@link
 * ShoppingList#postponeItemToList}.
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}
 * (AD-1). The application layer translates it into the client-localizable {@code
 * item.transferInProgress} code (a {@code 409}).
 */
public final class ItemTransferInProgressException extends RuntimeException {

    public ItemTransferInProgressException(String message) {
        super(message);
    }
}
