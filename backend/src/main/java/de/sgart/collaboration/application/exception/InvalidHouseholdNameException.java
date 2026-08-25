package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.command.CreateHouseholdHandler;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.shared.ErrorDescriptor;

/**
 * Raised when {@link CreateHouseholdHandler} rejects a caller-supplied household name — the
 * application-layer translation of the domain's own invariant failure into a stable,
 * client-localizable {@code code} (AC1). Carries the specific code so a blank name ({@code
 * household.nameRequired}) and an over-{@link de.sgart.collaboration.domain.HouseholdName#MAX_LENGTH}
 * name ({@code household.nameTooLong}) surface distinct copy, not one conflated message. Lives
 * here, not in {@code collaboration.domain}, so the write-side error advice in {@code adapter.in}
 * can catch it without reaching into the domain layer directly (the same pattern {@code
 * NotAMemberException} established in {@code identity.application}).
 */
public final class InvalidHouseholdNameException extends RuntimeException {

    private final ErrorDescriptor errorDescriptor;

    public InvalidHouseholdNameException(String code, String message) {
        super(message);
        this.errorDescriptor = ErrorDescriptor.of(code, message);
    }

    public ErrorDescriptor errorDescriptor() {
        return errorDescriptor;
    }
}
