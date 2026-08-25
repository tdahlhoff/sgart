package de.sgart.collaboration.application.query;

import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.readmodel.HouseholdNameReadModel;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The read side of first-run routing (AC2): which households does the caller belong to, and what
 * are their names? A pure query — no side effects (CLAUDE.md §6 CQRS coverage) — composing the
 * Identity ACL's published {@link ListHouseholdsForCaller} port (AD-2: never {@code
 * identity.domain} directly) with the household-name read model (AD-4).
 */
public final class ListMyHouseholds {

    private final ListHouseholdsForCaller listHouseholdsForCaller;
    private final HouseholdNameReadModel householdNameReadModel;

    public ListMyHouseholds(
            ListHouseholdsForCaller listHouseholdsForCaller, HouseholdNameReadModel householdNameReadModel) {
        this.listHouseholdsForCaller =
                Objects.requireNonNull(listHouseholdsForCaller, "listHouseholdsForCaller must not be null");
        this.householdNameReadModel =
                Objects.requireNonNull(householdNameReadModel, "householdNameReadModel must not be null");
    }

    /**
     * @return every household the caller belongs to, in no particular order. Membership is taken
     *     from the <em>authoritative</em> Identity ACL mapping (written synchronously by the mint),
     *     so the 0/1/≥2 routing count is never under-reported by a lagging projection. The display
     *     name is best-effort from the eventually-consistent name read model; a household whose
     *     name has not yet been projected still appears (with an empty name) rather than being
     *     dropped, so the caller is never misrouted as having fewer households than they do.
     */
    public List<HouseholdSummary> forCaller(String keycloakUserId) {
        List<HouseholdId> householdIds = listHouseholdsForCaller.forCaller(keycloakUserId);
        Map<HouseholdId, HouseholdName> names = householdNameReadModel.namesFor(householdIds);
        return householdIds.stream()
                .map(householdId -> new HouseholdSummary(householdId, nameOf(names.get(householdId))))
                .toList();
    }

    private static String nameOf(HouseholdName name) {
        return name == null ? "" : name.value();
    }

    /**
     * A household as seen by the caller: id + display name — the shape first-run routing needs.
     * {@code name} is a plain {@code String}, not the domain {@link HouseholdName}, so {@code
     * adapter.in} can consume this record without reaching into {@code collaboration.domain}.
     */
    public record HouseholdSummary(HouseholdId householdId, String name) {}
}
