package de.sgart.identity.domain;

import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Domain-owned port over the Identity ACL's new mutable {@code membership_nickname} table (Story
 * 8.3, AD-6 rev F). The domain declares the contract; {@code JdbcMembershipNicknameRepository}
 * implements it for production, {@code InMemoryMembershipNicknameRepository} for fast unit tests
 * (mirrors {@link MemberMappingRepository}).
 */
public interface MembershipNicknameRepository {

    /** Upserts the nickname for {@code (keycloakUserId, householdId)} — no uniqueness check. */
    void save(MembershipNickname membershipNickname);

    /** @return the nickname for {@code (keycloakUserId, householdId)}, or empty when unset. */
    Optional<String> find(KeycloakUserId keycloakUserId, HouseholdId householdId);

    /**
     * Resolves nicknames for a household's roster in one call, keyed by {@link MemberId} via the
     * {@link MemberMapping} join (the published {@code ResolveMembershipNicknames} port's read
     * path). A member with no nickname row yet is simply absent from the returned map — never a
     * placeholder value; the caller applies the neutral fallback.
     */
    Map<MemberId, String> resolveForHousehold(HouseholdId householdId, List<MemberId> memberIds);

    /**
     * Erasure (AD-7): removes every nickname row for {@code keycloakUserId}, across every
     * household — idempotent, a no-op when none exist. Epic 6 wires the trigger.
     */
    void deleteFor(KeycloakUserId keycloakUserId);

    /**
     * The governance de-link counterpart to {@code MemberMappingRepository.deleteMappingByMember}
     * (Story 4.3 symmetry): removes the nickname row for the member identified by {@code
     * (householdId, memberId)} — resolved via the {@link MemberMapping} join, since this table is
     * keyed by {@link KeycloakUserId}, not {@link MemberId}. Idempotent.
     */
    void deleteForMembership(HouseholdId householdId, MemberId memberId);

    /**
     * The governance de-link counterpart to {@code MemberMappingRepository.deleteAllMappings}
     * (a deleted household, Story 4.3, AC7): removes every nickname row for {@code householdId}.
     * Idempotent.
     */
    void deleteAllForHousehold(HouseholdId householdId);
}
