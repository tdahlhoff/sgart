package de.sgart.identity.domain;

import de.sgart.shared.HouseholdId;
import java.util.Objects;

/**
 * A person's self-chosen display name for one household (Story 8.3) — the documented, deliberate
 * exception to AD-6's "no persisted PII" rule (see the architecture spine, AD-6 rev F): freely
 * chosen by the person, never copied from a Keycloak claim, never written into a domain event, and
 * erasable like any other personal datum (CLAUDE.md §5; purpose, lawful basis, and retention in the
 * package-info).
 *
 * <p>Keyed by {@code (keycloakUserId, householdId)} — the same shape as {@link MemberMapping} but
 * addressed directly by {@link KeycloakUserId} since this table is written and read only within
 * the Identity ACL (a household's roster resolves it via the published {@code
 * ResolveMembershipNicknames} port, never this type). Trimmed, non-blank, and bounded ({@link
 * #MAX_LENGTH}) at construction — fail fast (CLAUDE.md §1), matching {@code HouseholdName}'s
 * convention. No uniqueness invariant: two people may choose the same nickname (Story 8.3, locked
 * decision).
 */
public record MembershipNickname(KeycloakUserId keycloakUserId, HouseholdId householdId, String nickname) {

    public static final int MAX_LENGTH = 60;

    public MembershipNickname {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(nickname, "nickname must not be null");
        nickname = nickname.trim();
        if (nickname.isBlank()) {
            throw new IllegalArgumentException("Nickname must not be blank");
        }
        if (nickname.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Nickname must not exceed " + MAX_LENGTH + " characters");
        }
    }
}
