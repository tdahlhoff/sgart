package de.sgart.identity.domain;

import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Optional;

/**
 * Domain-owned port over the Identity ACL's sole mapping {@code {householdId, memberId ->
 * keycloakUserId}} (AD-5). The domain declares the contract; an adapter implements it.
 *
 * <p><strong>This story ships an in-memory adapter only</strong> ({@code
 * InMemoryMemberMappingRepository}), seeded with synthetic mappings for tests — there is no
 * household or membership to persist yet. The durable PostgreSQL adapter, and the write/mint
 * path, land with the first writer in Story 1.6 (create-household); that story implements this
 * same interface without changing its contract.
 */
public interface MemberMappingRepository {

    /**
     * @return the mapped {@link MemberId} for a known {@code (keycloakUserId, householdId)} pair,
     *     or empty when the person is not a member of that household — never a newly minted id.
     */
    Optional<MemberId> findMemberId(KeycloakUserId keycloakUserId, HouseholdId householdId);
}
