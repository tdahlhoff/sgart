package de.sgart.collaboration.domain.event;

import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * A member left their household voluntarily (Story 4.3, AC3). Carries only the pseudonymous {@link
 * MemberId} — never a {@code keycloakUserId}, display name, or email (AD-5/AD-6). Raising this event
 * updates the aggregate's folded role map and the read model; it does not by itself revoke access —
 * the handler synchronously de-links the leaver's ACL mapping through {@code RetractMembership}
 * after the append (locked decision 3).
 */
public record MemberLeft(EventId eventId, HouseholdId householdId, MemberId memberId) implements DomainEvent {

    public MemberLeft {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(memberId, "memberId must not be null");
    }
}
