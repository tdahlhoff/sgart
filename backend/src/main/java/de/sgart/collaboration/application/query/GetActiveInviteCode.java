package de.sgart.collaboration.application.query;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.readmodel.HouseholdMemberReadModel;
import de.sgart.collaboration.domain.readmodel.InviteReadModel;
import de.sgart.collaboration.domain.readmodel.InviteView;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;
import java.util.Optional;

/**
 * The read side of the household's single invite code (Story 8.4): the active code, plus whether
 * the caller may replace it. A pure query — no side effects (CLAUDE.md §6 CQRS coverage) —
 * composing the Identity ACL's {@link ResolveMemberIdentity} port (AD-2) with the invite read model
 * (AD-4) and the member-roster read model ({@code canReplace} is derived from the caller's role
 * there, never stored redundantly). Mirrors {@code ListHouseholdMembers}.
 */
public final class GetActiveInviteCode {

    private final ResolveMemberIdentity resolveMemberIdentity;
    private final InviteReadModel inviteReadModel;
    private final HouseholdMemberReadModel householdMemberReadModel;

    public GetActiveInviteCode(
            ResolveMemberIdentity resolveMemberIdentity,
            InviteReadModel inviteReadModel,
            HouseholdMemberReadModel householdMemberReadModel) {
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.inviteReadModel = Objects.requireNonNull(inviteReadModel, "inviteReadModel must not be null");
        this.householdMemberReadModel =
                Objects.requireNonNull(householdMemberReadModel, "householdMemberReadModel must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if {@code rawHouseholdId} is missing or not a UUID (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     */
    public Optional<ActiveInviteCode> forHousehold(String keycloakUserId, String rawHouseholdId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);

        // Only a member may view a household's invite code — a non-member is a 403 (AD-2/AD-5).
        MemberId callerMemberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        return inviteReadModel.activeInviteOf(householdId).map(invite -> toSummary(invite, householdId, callerMemberId));
    }

    private ActiveInviteCode toSummary(InviteView invite, HouseholdId householdId, MemberId callerMemberId) {
        boolean canReplace = householdMemberReadModel.membersOf(householdId).stream()
                .anyMatch(member -> member.memberId().equals(callerMemberId) && member.role() == HouseholdRole.ADMIN);
        return new ActiveInviteCode(invite.inviteId().toString(), canReplace);
    }

    /**
     * The household's active invite code as seen by the caller: the id to share, and whether this
     * caller (an Admin) may replace it. Plain {@code String}/{@code boolean}, not domain types, so
     * {@code adapter.in} can consume this record without reaching into {@code collaboration.domain}.
     */
    public record ActiveInviteCode(String inviteId, boolean canReplace) {}
}
