package de.sgart.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.shared.HouseholdId;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves
 * {@link MembershipNickname}'s fail-fast invariant (Story 8.3): trims, rejects blank/whitespace,
 * rejects over-length.
 */
class MembershipNicknameTest {

    private final KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
    private final HouseholdId householdId = HouseholdId.generate();

    @Test
    void constructor_trimsSurroundingWhitespace() {
        MembershipNickname nickname = new MembershipNickname(keycloakUserId, householdId, "  Papa  ");

        assertThat(nickname.nickname()).isEqualTo("Papa");
    }

    @Test
    void constructor_rejectsABlankNickname() {
        assertThatThrownBy(() -> new MembershipNickname(keycloakUserId, householdId, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsAWhitespaceOnlyNickname() {
        assertThatThrownBy(() -> new MembershipNickname(keycloakUserId, householdId, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsANullNickname() {
        assertThatThrownBy(() -> new MembershipNickname(keycloakUserId, householdId, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsANicknameOverMaxLength() {
        String tooLong = "a".repeat(MembershipNickname.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new MembershipNickname(keycloakUserId, householdId, tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_acceptsANicknameAtExactlyMaxLength() {
        String exactlyMax = "a".repeat(MembershipNickname.MAX_LENGTH);

        MembershipNickname nickname = new MembershipNickname(keycloakUserId, householdId, exactlyMax);

        assertThat(nickname.nickname()).hasSize(MembershipNickname.MAX_LENGTH);
    }
}
