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
     *     or empty when the person is not a member of that household — never a newly issued id.
     */
    Optional<MemberId> findMemberId(KeycloakUserId keycloakUserId, HouseholdId householdId);

    /** Persists a newly issued mapping row. The Identity ACL is the sole caller (AD-5). */
    void save(MemberMapping mapping);

    /**
     * Removes the mapping row for {@code (keycloakUserId, householdId)} if present; a no-op when
     * none exists (idempotent). The Identity ACL is the sole caller (AD-5) — it compensates a
     * mapping written for a caller who then failed to complete their join.
     */
    void deleteMapping(KeycloakUserId keycloakUserId, HouseholdId householdId);

    /** @return every household the given person is a member of, in no particular order. */
    List<HouseholdId> householdIdsFor(KeycloakUserId keycloakUserId);

    /**
     * The reverse lookup of {@link #householdIdsFor(KeycloakUserId)} (Story 4.5, AC4): every
     * current member's {@link KeycloakUserId} for a household — the recipient-resolution seam the
     * notification fan-out's published port ({@code ResolveHouseholdPushTargets}) composes with
     * {@link DeviceTokenRepository} to reach only current members (AD-2, "mapping = access"). A
     * de-linked member is already absent here — no separate filtering needed by the caller.
     */
    List<KeycloakUserId> keycloakUserIdsFor(HouseholdId householdId);

    /**
     * The <strong>governance de-link</strong> (Story 4.3, AD-7): removes the mapping row for {@code
     * (householdId, memberId)} if present; a no-op when none exists (idempotent). Distinct from
     * {@link #deleteMapping(KeycloakUserId, HouseholdId)}'s join-failure compensation — this is the
     * mechanism that revokes access when a member leaves or is removed.
     */
    void deleteMappingByMember(HouseholdId householdId, MemberId memberId);

    /**
     * The <strong>governance de-link</strong> for a deleted household (Story 4.3, AD-7): removes
     * every mapping row for {@code householdId}; idempotent — a no-op when none exist.
     */
    void deleteAllMappings(HouseholdId householdId);
}
