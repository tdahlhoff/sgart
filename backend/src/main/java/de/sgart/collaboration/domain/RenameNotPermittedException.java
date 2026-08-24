package de.sgart.collaboration.domain;

/**
 * Raised by {@link Household#rename} when the requesting member is not an {@link HouseholdRole#ADMIN}
 * of the household (Story 1.7, AC4) — rename is an Admin-only capability enforced as a domain
 * invariant, not merely hidden in the UI.
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}: the
 * domain stays free of any outward-facing transport concern (AD-1), exactly as {@link HouseholdName}
 * throws a plain {@link IllegalArgumentException}. The application layer translates it into the
 * client-localizable {@code household.renameNotPermitted} code.
 */
public final class RenameNotPermittedException extends RuntimeException {

    public RenameNotPermittedException(String message) {
        super(message);
    }
}
