package de.sgart.identity.adapter.out;

import de.sgart.identity.application.FindHouseholdMemberByEmail;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Optional;

/**
 * The 4.1 implementation of {@link FindHouseholdMemberByEmail}: always resolves "unknown" (empty).
 * This is the <strong>fallback</strong> wired whenever {@code
 * sgart.identity.keycloak-admin.enabled=false} (the default — tests, CI, local dev). The real
 * Keycloak Admin API email→user lookup is {@code KeycloakAdminFindHouseholdMemberByEmail} (Story
 * 4.6, D4), config-gated in {@code IdentityBeansConfig}. Story 4.2's accept flow never needs
 * either: the joiner's {@code MemberId} is issued from their own JWT ({@code
 * IssueMemberIdentity}), never resolved by email.
 */
public final class DeferredFindHouseholdMemberByEmail implements FindHouseholdMemberByEmail {

    @Override
    public Optional<MemberId> forHousehold(String email, HouseholdId householdId) {
        return Optional.empty();
    }
}
