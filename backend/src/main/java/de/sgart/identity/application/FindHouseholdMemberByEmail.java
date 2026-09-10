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
 * <p>The 4.1 implementation, {@code DeferredFindHouseholdMemberByEmail}, is a stub that always
 * resolves to "unknown" (empty) — it stays wired whenever {@code
 * sgart.identity.keycloak-admin.enabled=false} (the default). The real adapter,
 * {@code KeycloakAdminFindHouseholdMemberByEmail} (Story 4.6, D4), resolves the email via the
 * Keycloak Admin REST API, config-gated so no admin credentials are needed to build or test. The
 * already-a-member seam (AC3) is exercised in unit tests via a fake that returns a member.
 */
public interface FindHouseholdMemberByEmail {

    Optional<MemberId> forHousehold(String email, HouseholdId householdId);
}
