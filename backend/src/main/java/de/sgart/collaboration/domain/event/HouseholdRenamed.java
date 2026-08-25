package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import java.util.Objects;

/**
 * A household was renamed (Story 1.7, AC3). Carries the household's id and its new name only —
 * never <em>who</em> renamed it: a rename is not personal data, and MVP tracks no rename audit
 * trail (AD-5/AD-6, YAGNI; Epic 4's governance may add attribution). The Admin-only authorization
 * is enforced by the {@link Household} aggregate before this event is ever raised (AC4), not
 * recorded on the event itself.
 */
public record HouseholdRenamed(EventId eventId, HouseholdId householdId, HouseholdName newName)
        implements DomainEvent {

    public HouseholdRenamed {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(newName, "newName must not be null");
    }
}
