package de.sgart.collaboration.domain.event;

import de.sgart.collaboration.domain.EmailHmac;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import java.time.Instant;
import java.util.Objects;

/**
 * A member invited a person by email (Story 4.1, AC1). The invite is an entity of {@link Household}
 * (AD-10), so this lives on the household stream. Carries {@link EmailHmac} — never the raw email
 * (AD-6): the digest is what makes the AC2 duplicate-pending check possible without persisting any
 * PII in the event stream. {@code role} is fixed to {@link HouseholdRole#PARTICIPANT} (locked
 * decision, §1) — invites never grant Admin.
 */
public record MemberInvited(
        EventId eventId,
        HouseholdId householdId,
        InviteId inviteId,
        EmailHmac emailHmac,
        MemberId invitedBy,
        HouseholdRole role,
        Instant invitedAt)
        implements DomainEvent {

    public MemberInvited {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(emailHmac, "emailHmac must not be null");
        Objects.requireNonNull(invitedBy, "invitedBy must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(invitedAt, "invitedAt must not be null");
    }
}
