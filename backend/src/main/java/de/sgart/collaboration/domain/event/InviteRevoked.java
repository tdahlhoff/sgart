package de.sgart.collaboration.domain.event;

import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * An Admin revoked a pending invite (Story 4.3, AC6) — the invite transitions to the terminal
 * {@code REVOKED} status. Carries only the pseudonymous {@link MemberId} of the revoker and the
 * {@link InviteId} — no email/HMAC (AD-6). The handler purges the invite's raw-email side-store row
 * after the append, mirroring the accept/expire purge points (AD-6, §2a).
 */
public record InviteRevoked(EventId eventId, HouseholdId householdId, InviteId inviteId, MemberId revokedBy)
        implements DomainEvent {

    public InviteRevoked {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(revokedBy, "revokedBy must not be null");
    }
}
