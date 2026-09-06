package de.sgart.collaboration.domain.readmodel;

import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import java.time.Instant;

/**
 * A pending invite as held in the read model (AD-4, Story 4.1, AC6/AC7) — id, when, who invited,
 * and status. Deliberately carries <strong>no email</strong> (AD-6): the pending-invites list shown
 * to household members is privacy-first by construction, not by convention.
 */
public record InviteView(InviteId inviteId, Instant invitedAt, MemberId invitedBy, String status) {}
