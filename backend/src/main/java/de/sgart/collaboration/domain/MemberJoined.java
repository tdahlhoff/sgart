package de.sgart.collaboration.domain;

import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.Objects;

/**
 * A person joined a household with a given {@link HouseholdRole} (AC1). Carries only the
 * Identity-ACL-minted {@link MemberId} — never a {@code keycloakUserId}, display name, or email
 * (AD-5/AD-6). In this story {@code role} is always {@link HouseholdRole#ADMIN} (the household
 * creator); Epic 4's invite-acceptance path reuses this same event with {@link
 * HouseholdRole#PARTICIPANT}.
 */
public record MemberJoined(EventId eventId, HouseholdId householdId, MemberId memberId, HouseholdRole role)
        implements DomainEvent {

    public MemberJoined {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(memberId, "memberId must not be null");
        Objects.requireNonNull(role, "role must not be null");
    }
}
