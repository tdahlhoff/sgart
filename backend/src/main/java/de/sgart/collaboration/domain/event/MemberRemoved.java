package de.sgart.collaboration.domain.event;

import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * An Admin removed another member from the household (Story 4.3, AC4). Carries only pseudonymous
 * {@link MemberId}s (AD-5/AD-6). Raising this event does not by itself revoke access — the handler
 * synchronously de-links the removed member's ACL mapping through {@code RetractMembership} after
 * the append (locked decision 3).
 */
public record MemberRemoved(EventId eventId, HouseholdId householdId, MemberId memberId, MemberId removedBy)
        implements DomainEvent {

    public MemberRemoved {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(memberId, "memberId must not be null");
        Objects.requireNonNull(removedBy, "removedBy must not be null");
    }
}
