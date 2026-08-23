package de.sgart.identity.domain;

import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * One row of the Identity ACL's sole mapping {@code {householdId, memberId -> keycloakUserId}}
 * (AD-5). A person who belongs to two households is represented by two unrelated
 * {@code MemberMapping}s, each with its own {@link MemberId}.
 *
 * <p>Erasure later must be able to locate and delete every mapping for a given
 * {@link KeycloakUserId} (AD-7) — keep that lookup shape in mind for the durable adapter that
 * replaces {@code InMemoryMemberMappingRepository} in Story 1.6.
 */
public record MemberMapping(HouseholdId householdId, MemberId memberId, KeycloakUserId keycloakUserId) {

    public MemberMapping {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(memberId, "memberId must not be null");
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
    }
}
