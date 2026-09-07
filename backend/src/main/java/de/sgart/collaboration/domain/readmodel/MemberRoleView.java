package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.shared.MemberId;

/**
 * A member's roster row as held in the read model (AD-4, Story 4.3, AC8) — id + role only. No
 * PII (AD-6, decision 5): the {@code isSelf} flag and any display language ("Sie") are computed at
 * the query/presentation layer, not stored.
 */
public record MemberRoleView(MemberId memberId, HouseholdRole role) {}
