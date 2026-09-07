package de.sgart.identity.adapter.out;

import de.sgart.identity.application.FindHouseholdMemberByEmail;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Optional;

/**
 * The 4.1 implementation of {@link FindHouseholdMemberByEmail}: always resolves "unknown" (empty).
 * Deliberately deferred (locked decision 2, Story 4.1) — a real Keycloak Admin API email→user
 * lookup ships in Story 4.6. Story 4.2's accept flow never needs it: the joiner's {@code MemberId}
 * is minted from their own JWT ({@code MintMemberIdentity}), never resolved by email. Until 4.6,
 * every invite email is treated as belonging to nobody yet, which is correct: no second member
 * exists to already be one.
 */
public final class DeferredFindHouseholdMemberByEmail implements FindHouseholdMemberByEmail {

    @Override
    public Optional<MemberId> forHousehold(String email, HouseholdId householdId) {
        return Optional.empty();
    }
}
