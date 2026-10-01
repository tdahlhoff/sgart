package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.readmodel.HouseholdNameReadModel;
import de.sgart.identity.application.FindHouseholdNames;
import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Implements identity's {@link FindHouseholdNames} out-port from the household-name read model, for
 * the recovery account picker. Like {@link IdentityConsentGate} it lives in {@code adapter.out}, so
 * only this driven side of the port knows {@code identity} exists.
 */
public final class CollaborationHouseholdNames implements FindHouseholdNames {

    private final HouseholdNameReadModel householdNameReadModel;

    public CollaborationHouseholdNames(HouseholdNameReadModel householdNameReadModel) {
        this.householdNameReadModel =
                Objects.requireNonNull(householdNameReadModel, "householdNameReadModel must not be null");
    }

    @Override
    public Map<HouseholdId, String> namesFor(List<HouseholdId> householdIds) {
        return householdNameReadModel.namesFor(householdIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().value()));
    }
}
