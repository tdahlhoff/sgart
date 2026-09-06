package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.event.InviteExpired;
import de.sgart.collaboration.domain.event.MemberInvited;
import de.sgart.shared.HouseholdId;
import java.util.List;

/**
 * Domain-owned port over the invite CQRS read model (AD-4, Story 4.1, AC6) — built solely by {@code
 * HouseholdReadModelProjector} folding {@link MemberInvited}/{@link InviteExpired}; a command
 * handler never writes it. {@code ListPendingInvites} is the query that reads through this port.
 * Carries no email/HMAC column (AD-6) — only {@code invitedAt}/{@code invitedBy}/{@code status}.
 * Mirrors {@code StoreReadModel}. Deliberately does not import {@code adapter.out} or {@code
 * application} types here — a domain port must not depend outward (AD-1/AD-2); the projector and
 * query are named in plain {@code @code} text, not {@code @link}.
 */
public interface InviteReadModel {

    /** @return the household's pending (non-expired) invites, most recently invited first. */
    List<InviteView> pendingInvitesOf(HouseholdId householdId);
}
