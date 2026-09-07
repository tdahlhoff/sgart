package de.sgart.identity.application;

import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * The Identity ACL's <strong>governance de-link</strong> port (Story 4.3, AD-7) — the published,
 * cross-context seam the Collaboration governance handlers call to revoke access after a successful
 * append (locked decision 3): {@code ListHouseholdsForCaller.forCaller} and {@code
 * ResolveMemberIdentity.resolve} derive membership solely from the {@code identity_member_mapping}
 * table, with no event-stream cross-check, so a removed/left member (or every member of a deleted
 * household) keeps full access until this port de-links their mapping. Governance de-linking's
 * sibling to {@link IssueMemberIdentity}'s {@code retract} (a join-failure compensation) — a
 * distinct use case, called from a different trigger (a successful governance command, not a failed
 * join). Never called directly against {@code identity.domain} or the mapping table (AD-2).
 */
public final class RetractMembership {

    private final MemberMappingRepository memberMappingRepository;

    public RetractMembership(MemberMappingRepository memberMappingRepository) {
        this.memberMappingRepository =
                Objects.requireNonNull(memberMappingRepository, "memberMappingRepository must not be null");
    }

    /** De-links a single member's mapping — the leaver's own, or the target of a removal. Idempotent. */
    public void retractMember(HouseholdId householdId, MemberId memberId) {
        memberMappingRepository.deleteMappingByMember(householdId, memberId);
    }

    /** De-links every mapping for a deleted household (Story 4.3, AC7). Idempotent. */
    public void retractHousehold(HouseholdId householdId) {
        memberMappingRepository.deleteAllMappings(householdId);
    }
}
