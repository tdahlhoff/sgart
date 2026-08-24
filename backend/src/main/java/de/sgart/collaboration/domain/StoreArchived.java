package de.sgart.collaboration.domain;

import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StoreId;
import java.util.Objects;

/**
 * A store was archived (Story 1.8, AC3) — a soft state change that hides it from all future
 * selection <em>without</em> deleting the store or any historical trip/assignment that referenced
 * it (FR3). Like {@link StoreAdded} it lives on the household stream, since {@code Store} is an
 * entity of {@link Household} (AD-10). Carries the ids only — never who archived it (no audit trail
 * in the MVP, AD-5/AD-6; YAGNI).
 */
public record StoreArchived(EventId eventId, HouseholdId householdId, StoreId storeId)
        implements DomainEvent {

    public StoreArchived {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
    }
}
