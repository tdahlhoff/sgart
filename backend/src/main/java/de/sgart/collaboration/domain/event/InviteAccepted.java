package de.sgart.collaboration.domain.event;

import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * An invite was redeemed (Story 4.2, AC1/AC2/AC4) — raised whenever {@code acceptInvite} consumes a
 * pending invite, whether or not the joiner was already a member (AC4's no-op join still consumes
 * the invite). Carries only the Identity-ACL-issued joiner {@link MemberId} — never an email, HMAC,
 * or {@code keycloakUserId} (AD-5/AD-6); which invite was consumed is identified by {@link
 * #inviteId()} alone.
 */
public record InviteAccepted(EventId eventId, HouseholdId householdId, InviteId inviteId, MemberId memberId)
        implements DomainEvent {

    public InviteAccepted {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(memberId, "memberId must not be null");
    }
}
