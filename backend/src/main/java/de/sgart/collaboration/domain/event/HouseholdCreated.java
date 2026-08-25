package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import java.util.Objects;

/**
 * A household was created (AC1). Carries the household's id and name only — no PII (AD-5/AD-6):
 * the creator's identity is represented in the {@link MemberJoined} event that always follows,
 * never here.
 */
public record HouseholdCreated(EventId eventId, HouseholdId householdId, HouseholdName name)
        implements DomainEvent {

    public HouseholdCreated {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}
