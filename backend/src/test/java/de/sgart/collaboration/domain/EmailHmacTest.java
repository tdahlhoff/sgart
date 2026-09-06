package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test (CLAUDE.md §6) — {@link EmailHmac} carries only a digest and is
 * compared by it (AD-6: it never holds or derives the raw email).
 */
class EmailHmacTest {

    @Test
    void equals_treatsTwoDigestsWithTheSameValueAsEqual() {
        assertThat(new EmailHmac("abc123")).isEqualTo(new EmailHmac("abc123"));
    }

    @Test
    void equals_treatsTwoDifferentDigestsAsNotEqual() {
        assertThat(new EmailHmac("abc123")).isNotEqualTo(new EmailHmac("def456"));
    }

    @Test
    void constructor_rejectsANullDigest() {
        assertThatThrownBy(() -> new EmailHmac(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsABlankDigest() {
        assertThatThrownBy(() -> new EmailHmac("   ")).isInstanceOf(IllegalArgumentException.class);
    }
}
