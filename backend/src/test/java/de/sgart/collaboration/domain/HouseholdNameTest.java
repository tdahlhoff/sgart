package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * {@link HouseholdName} invariant: non-blank, trimmed, bounded length (AC1, fail fast).
 */
class HouseholdNameTest {

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        HouseholdName name = new HouseholdName("  Familie Muster  ");

        assertThat(name.value()).isEqualTo("Familie Muster");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new HouseholdName("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAWhitespaceOnlyName() {
        assertThatThrownBy(() -> new HouseholdName("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullName() {
        assertThatThrownBy(() -> new HouseholdName(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANameExceedingTheMaxLength() {
        String tooLong = "a".repeat(HouseholdName.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new HouseholdName(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsANameAtExactlyTheMaxLength() {
        String exactlyMax = "a".repeat(HouseholdName.MAX_LENGTH);

        assertThat(new HouseholdName(exactlyMax).value()).hasSize(HouseholdName.MAX_LENGTH);
    }
}
