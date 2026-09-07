package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.Household;

/**
 * Raised by {@link Household} when a leave/remove/demote would drop the household's last Admin
 * (Story 4.3, AC5) — the at-least-one-Admin invariant. A plain domain exception carrying no
 * client-facing {@code code}/{@code ErrorDescriptor} (AD-1); the application layer translates it
 * into the client-localizable {@code membership.lastAdmin} code, a {@code 409}.
 */
public final class LastAdminException extends RuntimeException {

    public LastAdminException(String message) {
        super(message);
    }
}
