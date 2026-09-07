package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.Household;

/**
 * Raised by {@link Household} when a governance action (remove/promote/demote/delete/revoke) is
 * attempted by a caller who is not an Admin (Story 4.3, AC2, AC6) — governance is Admin-only, unlike
 * the membership-gated daily commands (AC1). A plain domain exception carrying no client-facing
 * {@code code}/{@code ErrorDescriptor} (AD-1); the application layer translates it into the
 * client-localizable {@code governance.notPermitted} code, a {@code 403}.
 */
public final class GovernanceNotPermittedException extends RuntimeException {

    public GovernanceNotPermittedException(String message) {
        super(message);
    }
}
