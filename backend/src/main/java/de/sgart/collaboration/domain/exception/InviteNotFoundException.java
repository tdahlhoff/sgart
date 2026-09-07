package de.sgart.collaboration.domain.exception;

import de.sgart.collaboration.domain.Household;

/**
 * Raised by {@link Household#acceptInvite} when the given {@code InviteId} is not on the household
 * stream at all (Story 4.2, AC5) — a clean {@code 404} rather than accepting a phantom invite. A
 * plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor} (AD-1);
 * mirrors {@link ItemNotFoundException}.
 */
public final class InviteNotFoundException extends RuntimeException {

    public InviteNotFoundException(String message) {
        super(message);
    }
}
