package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * {@link StoreName} invariant: non-blank, trimmed, bounded length (AC1, fail fast). Mirrors
 * {@link HouseholdNameTest}.
 */
class StoreNameTest {

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        StoreName name = new StoreName("  Edeka Schiedemann  ");

        assertThat(name.value()).isEqualTo("Edeka Schiedemann");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new StoreName("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAWhitespaceOnlyName() {
        assertThatThrownBy(() -> new StoreName("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullName() {
        assertThatThrownBy(() -> new StoreName(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANameExceedingTheMaxLength() {
        String tooLong = "a".repeat(StoreName.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new StoreName(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsANameAtExactlyTheMaxLength() {
        String exactlyMax = "a".repeat(StoreName.MAX_LENGTH);

        assertThat(new StoreName(exactlyMax).value()).hasSize(StoreName.MAX_LENGTH);
    }
}
