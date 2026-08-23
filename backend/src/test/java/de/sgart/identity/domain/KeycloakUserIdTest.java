package de.sgart.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6).
 */
class KeycloakUserIdTest {

    @Test
    void constructor_rejectsANullValue() {
        assertThatThrownBy(() -> new KeycloakUserId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsABlankValue() {
        assertThatThrownBy(() -> new KeycloakUserId("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equals_treatsTwoIdsWithTheSameValueAsEqual() {
        assertThat(new KeycloakUserId("sub-123")).isEqualTo(new KeycloakUserId("sub-123"));
    }
}
