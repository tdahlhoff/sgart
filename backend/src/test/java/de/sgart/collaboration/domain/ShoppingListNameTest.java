package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * {@link ShoppingListName} invariant: non-blank, trimmed, bounded length (AC1, fail fast).
 */
class ShoppingListNameTest {

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        ShoppingListName name = new ShoppingListName("  Wocheneinkauf  ");

        assertThat(name.value()).isEqualTo("Wocheneinkauf");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new ShoppingListName("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAWhitespaceOnlyName() {
        assertThatThrownBy(() -> new ShoppingListName("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullName() {
        assertThatThrownBy(() -> new ShoppingListName(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANameExceedingTheMaxLength() {
        String tooLong = "a".repeat(ShoppingListName.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new ShoppingListName(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsANameAtExactlyTheMaxLength() {
        String exactlyMax = "a".repeat(ShoppingListName.MAX_LENGTH);

        assertThat(new ShoppingListName(exactlyMax).value()).hasSize(ShoppingListName.MAX_LENGTH);
    }
}
