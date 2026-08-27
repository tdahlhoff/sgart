package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * {@link ItemName} invariant: non-blank, trimmed, bounded length (AC1, fail fast).
 */
class ItemNameTest {

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        ItemName name = new ItemName("  Milch  ");

        assertThat(name.value()).isEqualTo("Milch");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new ItemName("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAWhitespaceOnlyName() {
        assertThatThrownBy(() -> new ItemName("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullName() {
        assertThatThrownBy(() -> new ItemName(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANameExceedingTheMaxLength() {
        String tooLong = "a".repeat(ItemName.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new ItemName(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsANameAtExactlyTheMaxLength() {
        String exactlyMax = "a".repeat(ItemName.MAX_LENGTH);

        assertThat(new ItemName(exactlyMax).value()).hasSize(ItemName.MAX_LENGTH);
    }
}
