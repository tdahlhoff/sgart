package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.event.HouseholdDeleted;
import de.sgart.collaboration.domain.event.MemberDemoted;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.MemberLeft;
import de.sgart.collaboration.domain.event.MemberPromoted;
import de.sgart.collaboration.domain.event.MemberRemoved;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;

/**
 * Domain-owned port over the member-roster CQRS read model (AD-4, Story 4.3, AC8) — built solely by
 * {@code HouseholdReadModelProjector} folding {@link MemberJoined}/{@link MemberPromoted}/{@link
 * MemberDemoted}/{@link MemberLeft}/{@link MemberRemoved}/{@link HouseholdDeleted}; a command
 * handler never writes it. {@code ListHouseholdMembers} is the query that reads through this port.
 * Carries no PII column (AD-6, decision 5) — only {@code (householdId, memberId, role)}. Mirrors
 * {@code InviteReadModel}. Deliberately does not import {@code adapter.out} or {@code application}
 * types (AD-1/AD-2).
 */
public interface HouseholdMemberReadModel {

    /** @return every current member of the household — no roster row for a left/removed member. */
    List<MemberRoleView> membersOf(HouseholdId householdId);
}
