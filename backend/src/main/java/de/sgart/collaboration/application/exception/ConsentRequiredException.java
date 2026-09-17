package de.sgart.collaboration.application.exception;

import de.sgart.shared.ErrorDescriptor;

/**
 * Raised by {@code CreateHouseholdHandler}/{@code AcceptInviteHandler} when the caller has no
 * recorded consent for processing household personal data (Story 7.4, AC3, D-B server fail-fast).
 * Mapped to {@code 409 consent.required} — not {@code 403}, which would read as an auth failure —
 * so a stale client recovers by showing the consent screen again.
 */
public final class ConsentRequiredException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public ConsentRequiredException(String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of("consent.required", message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
