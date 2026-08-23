package de.sgart.identity.domain;

import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Optional;

/**
 * Domain-owned port over the Identity ACL's sole mapping {@code {householdId, memberId ->
 * keycloakUserId}} (AD-5). The domain declares the contract; an adapter implements it.
 *
 * <p>The write side ({@link #save(MemberMapping)}) and the caller-lookup ({@link
 * #householdIdsFor(KeycloakUserId)}, needed by first-run routing) landed with the first writer in
 * Story 1.6 (create-household), alongside the durable PostgreSQL adapter ({@code
 * JdbcMemberMappingRepository}) that replaces the in-memory one in production. {@code
 * InMemoryMemberMappingRepository} remains the fast unit-test double.
 *
 * <p>The row shape is deliberately erasure-locatable by {@link KeycloakUserId} alone (AD-7): a
 * future erasure use case can find and delete every mapping for a person via {@link
 * #householdIdsFor(KeycloakUserId)} without needing any other index.
 */
public interface MemberMappingRepository {

    /**
     * @return the mapped {@link MemberId} for a known {@code (keycloakUserId, householdId)} pair,
     *     or empty when the person is not a member of that household — never a newly minted id.
     */
    Optional<MemberId> findMemberId(KeycloakUserId keycloakUserId, HouseholdId householdId);

    /** Persists a newly minted mapping row. The Identity ACL is the sole caller (AD-5). */
    void save(MemberMapping mapping);

    /** @return every household the given person is a member of, in no particular order. */
    List<HouseholdId> householdIdsFor(KeycloakUserId keycloakUserId);
}
