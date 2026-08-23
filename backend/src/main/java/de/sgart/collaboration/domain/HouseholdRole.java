package de.sgart.collaboration.domain;

/**
 * A member's participation level within one household (glossary term — never bare "Member" for
 * the role, AD-11). {@code ADMIN} is the household creator (AC1); {@code PARTICIPANT} is the
 * Epic 4 invite-acceptance role, reusing the same {@link MemberJoined} event.
 */
public enum HouseholdRole {
    ADMIN,
    PARTICIPANT
}
