package de.sgart.collaboration.domain.event;

import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import java.util.Objects;

/**
 * A pending invite passed its TTL (Story 4.1, AC5) — raised lazily as housekeeping when the same
 * email is invited again, never by a standalone command. Carries no email/HMAC: the read model and
 * fold only need to know which invite expired.
 */
public record InviteExpired(EventId eventId, HouseholdId householdId, InviteId inviteId) implements DomainEvent {

    public InviteExpired {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
    }
}
