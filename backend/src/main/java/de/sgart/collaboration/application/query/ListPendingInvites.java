package de.sgart.collaboration.application.query;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.domain.readmodel.InviteReadModel;
import de.sgart.collaboration.domain.readmodel.InviteView;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Objects;

/**
 * The read side of invite management (Story 4.1, AC6): the household's pending invites. A pure
 * query — no side effects (CLAUDE.md §6 CQRS coverage) — composing the Identity ACL's {@link
 * ResolveMemberIdentity} port (AD-2) with the invite read model (AD-4). Mirrors {@code ListStores}.
 */
public final class ListPendingInvites {

    private final ResolveMemberIdentity resolveMemberIdentity;
    private final InviteReadModel inviteReadModel;

    public ListPendingInvites(ResolveMemberIdentity resolveMemberIdentity, InviteReadModel inviteReadModel) {
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
        this.inviteReadModel = Objects.requireNonNull(inviteReadModel, "inviteReadModel must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5).
     * @throws InvalidCommandEnvelopeException if {@code rawHouseholdId} is missing or not a UUID (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     */
    public List<PendingInvite> forHousehold(String keycloakUserId, String rawHouseholdId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);

        // Only a member may list a household's invites — a non-member is a 403 (AD-2/AD-5).
        resolveMemberIdentity.resolve(keycloakUserId, householdId);

        return inviteReadModel.pendingInvitesOf(householdId).stream()
                .map(ListPendingInvites::toSummary)
                .toList();
    }

    private static PendingInvite toSummary(InviteView invite) {
        return new PendingInvite(
                invite.inviteId().toString(),
                invite.invitedAt().toString(),
                invite.invitedBy().toString(),
                invite.status());
    }

    /**
     * A pending invite as seen by the caller: no email (AD-6, privacy-first). Plain {@code
     * String}s, not domain types, so {@code adapter.in} can consume this record without reaching
     * into {@code collaboration.domain}.
     */
    public record PendingInvite(String inviteId, String invitedAt, String invitedBy, String status) {}
}
