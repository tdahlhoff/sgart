package de.sgart.identity.application;

import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Optional;

/**
 * The Identity ACL's email-resolution port — {@code (email, householdId) -> MemberId} (Story 4.1,
 * AC3, E5). Published entry point takes a plain {@code String} email, never a domain email type, so
 * a cross-context caller (the Collaboration invite handler) never has to reach into {@code
 * identity.domain} (AD-2), mirroring {@link ResolveMemberIdentity#resolve(String, HouseholdId)}.
 *
 * <p>The 4.1 implementation is a deferred stub that always resolves to "unknown" (empty) — the real
 * Keycloak Admin API email→user lookup ships in Stories 4.2/4.6 (locked decision 2), since no
 * second real user can exist in a household before 4.2's accept flow lands. The already-a-member
 * seam (AC3) is exercised now via a fake that returns a member.
 */
public interface FindHouseholdMemberByEmail {

    Optional<MemberId> forHousehold(String email, HouseholdId householdId);
}
