package de.sgart.identity.adapter.out;

import de.sgart.identity.application.FindHouseholdMemberByEmail;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Optional;

/**
 * The 4.1 implementation of {@link FindHouseholdMemberByEmail}: always resolves "unknown" (empty).
 * Deliberately deferred (locked decision 2, Story 4.1) — a real Keycloak Admin API email→user
 * lookup ships in Stories 4.2/4.6, once a second real user can exist in a household (4.2's accept
 * flow). Until then, every invite email is treated as belonging to nobody yet, which is correct:
 * no second member exists to already be one.
 */
public final class DeferredFindHouseholdMemberByEmail implements FindHouseholdMemberByEmail {

    @Override
    public Optional<MemberId> forHousehold(String email, HouseholdId householdId) {
        return Optional.empty();
    }
}
