package de.sgart.collaboration.domain.event;

import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * An Admin promoted a Participant to Admin (Story 4.3, AC4). Carries only pseudonymous
 * {@link MemberId}s (AD-5/AD-6). A role change, not a membership removal — no ACL de-link follows
 * this event.
 */
public record MemberPromoted(EventId eventId, HouseholdId householdId, MemberId memberId, MemberId promotedBy)
        implements DomainEvent {

    public MemberPromoted {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(memberId, "memberId must not be null");
        Objects.requireNonNull(promotedBy, "promotedBy must not be null");
    }
}
