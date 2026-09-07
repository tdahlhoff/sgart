package de.sgart.collaboration.domain.event;

import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * An Admin deleted the household (Story 4.3, AC7). Carries only the pseudonymous {@link MemberId}
 * of the deleter (AD-5/AD-6). Raising this event does not by itself revoke access or purge the read
 * models — the handler synchronously de-links every ACL mapping for the household through {@code
 * RetractMembership} after the append (locked decision 3), and the three collaboration projectors
 * purge their {@code householdId}-keyed read-model rows on projecting this event (decision 4).
 * Per-aggregate event streams are left intact (Epic 6 owns deep erasure).
 */
public record HouseholdDeleted(EventId eventId, HouseholdId householdId, MemberId deletedBy)
        implements DomainEvent {

    public HouseholdDeleted {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(deletedBy, "deletedBy must not be null");
    }
}
