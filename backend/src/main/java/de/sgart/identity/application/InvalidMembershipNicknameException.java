package de.sgart.identity.application;

import de.sgart.identity.domain.MembershipNickname;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when {@link SetMembershipNickname} rejects a caller-supplied nickname — the
 * application-layer translation of {@link MembershipNickname}'s own invariant failure into a
 * stable, client-localizable {@code code} (Story 8.3). Carries the specific code so a blank
 * nickname ({@code nickname.required}) and an over-{@link MembershipNickname#MAX_LENGTH} nickname
 * ({@code nickname.tooLong}) surface distinct copy — the same pattern {@code
 * InvalidHouseholdNameException} established in {@code collaboration.application}.
 */
public final class InvalidMembershipNicknameException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidMembershipNicknameException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
