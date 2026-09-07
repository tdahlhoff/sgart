package de.sgart.collaboration.application.query;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.domain.readmodel.HouseholdMemberReadModel;
import de.sgart.collaboration.domain.readmodel.MemberRoleView;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Objects;

/**
 * The read side of member management (Story 4.3, AC8): the household's member roster. A pure
 * query — no side effects (CLAUDE.md §6 CQRS coverage) — composing the Identity ACL's {@link
 * ResolveMemberIdentity} port (AD-2) with the member read model (AD-4). Mirrors {@code
 * ListPendingInvites}. Each row carries only {@code memberId}/{@code role} (AD-6, decision 5); the
 * caller is flagged {@code isSelf} here, computed from the resolved caller id — never stored.
 */
public final class ListHouseholdMembers {

    private final ResolveMemberIdentity resolveMemberIdentity;
    private final HouseholdMemberReadModel householdMemberReadModel;

    public ListHouseholdMembers(
            ResolveMemberIdentity resolveMemberIdentity, HouseholdMemberReadModel householdMemberReadModel) {
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.householdMemberReadModel =
                Objects.requireNonNull(householdMemberReadModel, "householdMemberReadModel must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if {@code rawHouseholdId} is missing or not a UUID (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     */
    public List<MemberSummary> forHousehold(String keycloakUserId, String rawHouseholdId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);

        // Only a member may list a household's roster — a non-member is a 403 (AD-2/AD-5).
        MemberId callerMemberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        return householdMemberReadModel.membersOf(householdId).stream()
                .map(member -> toSummary(member, callerMemberId))
                .toList();
    }

    private static MemberSummary toSummary(MemberRoleView member, MemberId callerMemberId) {
        return new MemberSummary(
                member.memberId().toString(), member.role().name(), member.memberId().equals(callerMemberId));
    }

    /**
     * A member as seen by the caller: id + role + whether this row is the caller themselves — no
     * PII (AD-6, decision 5). Plain {@code String}s, not domain types, so {@code adapter.in} can
     * consume this record without reaching into {@code collaboration.domain}.
     */
    public record MemberSummary(String memberId, String role, boolean isSelf) {}
}
