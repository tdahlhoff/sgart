package de.sgart.identity.application;

import de.sgart.identity.domain.MembershipNicknameRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Identity ACL's nickname read port (Story 8.3) — the published, boundary-safe query the
 * Collaboration member roster ({@code MemberController}) calls to enrich each row with its
 * resolved nickname (AD-2, mirrors {@link ResolveMemberIdentity}). A pure query: no side effects
 * (CLAUDE.md §6 CQRS coverage). {@code HouseholdId}/{@code MemberId} are shared-kernel types, not
 * {@code identity.domain} types, so this signature crosses the context boundary safely.
 */
public final class ResolveMembershipNicknames {

    private final MembershipNicknameRepository membershipNicknameRepository;

    public ResolveMembershipNicknames(MembershipNicknameRepository membershipNicknameRepository) {
        this.membershipNicknameRepository =
                Objects.requireNonNull(membershipNicknameRepository, "membershipNicknameRepository must not be null");
    }

    /**
     * @return the resolved nickname per {@link MemberId}; a member with no nickname set yet is
     *     simply absent from the map — the caller applies the neutral fallback (never a raw id).
     */
    public Map<MemberId, String> resolveFor(HouseholdId householdId, List<MemberId> memberIds) {
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(memberIds, "memberIds must not be null");
        return membershipNicknameRepository.resolveForHousehold(householdId, memberIds);
    }
}
