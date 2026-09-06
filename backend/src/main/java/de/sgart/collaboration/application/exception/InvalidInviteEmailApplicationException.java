package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.NormalizedEmail;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when {@link NormalizedEmail#fromRaw} rejects a caller-supplied invite email (Story 4.1,
 * AC1/AC3) — a fail-fast {@code 400}. Carries a specific code so a missing email ({@code
 * invite.emailRequired}) and a malformed one ({@code invite.emailInvalid}) surface distinct copy.
 * Mirrors {@link InvalidStoreNameException}; lives here, not in {@code collaboration.domain} (AD-6:
 * the domain never sees an email at all).
 */
public final class InvalidInviteEmailApplicationException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidInviteEmailApplicationException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
