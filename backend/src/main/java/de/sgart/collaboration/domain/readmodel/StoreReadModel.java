package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.adapter.out.HouseholdReadModelProjector;
import de.sgart.collaboration.application.query.ListStores;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.shared.HouseholdId;
import java.util.List;

/**
 * Domain-owned port over the store CQRS read model (AD-4) — built solely by {@code
 * HouseholdReadModelProjector} folding {@code StoreAdded}/{@code StoreArchived}; a command handler
 * never writes it. {@code ListStores} (application layer) is the query that reads through this port.
 *
 * <p>Exposes <strong>active stores only</strong> — this is the AC5 structural guarantee: every
 * current and future picker/grouping reads through here, so an archived store is never offered and
 * an item pointing at an archived store naturally has no active target (surfaces as unassigned).
 */
public interface StoreReadModel {

    /** @return the household's active (non-archived) stores, in no particular order. */
    List<StoreView> activeStoresOf(HouseholdId householdId);
}
