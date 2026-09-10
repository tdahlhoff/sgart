package de.sgart.identity.application;

import java.util.Objects;

/**
 * One resolved push destination — a device token plus its platform, as plain {@code String}s
 * (Story 4.5, AC3/AC4). Deliberately not {@link de.sgart.identity.domain.DeviceToken}: this type
 * is the one that crosses the Identity/Collaboration boundary (returned by {@link
 * ResolveHouseholdPushTargets}, consumed by Collaboration's {@code ContentFreePushSender} port),
 * so it stays free of {@code identity.domain} types the same way {@code
 * ListHouseholdMembers.MemberSummary} keeps {@code HouseholdRole} out of its cross-context shape
 * (AD-2 — enforced by {@code HexagonalArchitectureTest}'s
 * {@code collaborationApplicationDoesNotReachIntoIdentityDomain} rule).
 */
public record PushTarget(String token, String platform) {

    public PushTarget {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
    }
}
