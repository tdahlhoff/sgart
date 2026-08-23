package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * The Identity ACL's resolution port — {@code (keycloakUserId, householdId) -> MemberId} (AD-5).
 * A query use case: it never mints or writes, only translates the caller's Keycloak identity into
 * the household-scoped pseudonym that every later household-scoped command/query is meant to call
 * before touching the Collaboration domain.
 */
public final class ResolveMemberIdentity {

    private final MemberMappingRepository memberMappingRepository;

    public ResolveMemberIdentity(MemberMappingRepository memberMappingRepository) {
        this.memberMappingRepository =
                Objects.requireNonNull(memberMappingRepository, "memberMappingRepository must not be null");
    }

    /**
     * @throws NotAMemberException when the caller has no mapping for the given household — never
     *     a silent mint.
     */
    public MemberId resolve(KeycloakUserId keycloakUserId, HouseholdId householdId) {
        return memberMappingRepository
                .findMemberId(keycloakUserId, householdId)
                .orElseThrow(NotAMemberException::new);
    }
}
