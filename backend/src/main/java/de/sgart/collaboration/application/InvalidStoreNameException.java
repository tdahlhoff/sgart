package de.sgart.collaboration.application;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when {@link AddStoreHandler} rejects a caller-supplied store name — the application-layer
 * translation of the domain's own invariant failure into a stable, client-localizable {@code code}
 * (AC1). Carries the specific code so a blank name ({@code store.nameRequired}) and an
 * over-{@link de.sgart.collaboration.domain.StoreName#MAX_LENGTH} name ({@code store.nameTooLong})
 * surface distinct copy. Mirrors {@link InvalidHouseholdNameException}; lives here, not in {@code
 * collaboration.domain}, so the write-side error advice in {@code adapter.in} can catch it without
 * reaching into the domain layer.
 */
public final class InvalidStoreNameException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    InvalidStoreNameException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
