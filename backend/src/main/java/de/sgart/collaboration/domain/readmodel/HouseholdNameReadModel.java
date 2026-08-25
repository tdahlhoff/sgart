package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.adapter.out.HouseholdReadModelProjector;
import de.sgart.collaboration.application.query.ListMyHouseholds;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Map;

/**
 * Domain-owned port over the household-name CQRS read model (AD-4) — built solely by {@code
 * HouseholdReadModelProjector} folding {@code HouseholdCreated}; a command handler never writes
 * it. {@code ListMyHouseholds} (application layer) is the query that reads through this port.
 */
public interface HouseholdNameReadModel {

    /** @return the known name for each of {@code householdIds} that has been projected so far. */
    Map<HouseholdId, HouseholdName> namesFor(List<HouseholdId> householdIds);
}
