package de.sgart.identity.application;

import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Map;

/**
 * Identity-owned out-port for the one foreign datum the recovery account picker needs: the display
 * name of a household. Household ids and nicknames identity already holds itself; names live in
 * the collaboration context, which implements this port ({@code collaboration.adapter.out}), so
 * the dependency points collaboration to identity only. A household whose name is not projected
 * yet is simply absent from the result.
 */
public interface FindHouseholdNames {

    Map<HouseholdId, String> namesFor(List<HouseholdId> householdIds);
}
