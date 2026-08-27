package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * {@link ItemNote} invariant: non-blank-when-present, trimmed, bounded length (AC1/AC2, fail fast).
 * Absence is represented by a {@code null} reference, not an instance of this type.
 */
class ItemNoteTest {

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        ItemNote note = new ItemNote("  Bio  ");

        assertThat(note.value()).isEqualTo("Bio");
    }

    @Test
    void rejectsABlankNote() {
        assertThatThrownBy(() -> new ItemNote("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAWhitespaceOnlyNote() {
        assertThatThrownBy(() -> new ItemNote("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullNote() {
        assertThatThrownBy(() -> new ItemNote(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANoteExceedingTheMaxLength() {
        String tooLong = "a".repeat(ItemNote.MAX_LENGTH + 1);

        assertThatThrownBy(() -> new ItemNote(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsANoteAtExactlyTheMaxLength() {
        String exactlyMax = "a".repeat(ItemNote.MAX_LENGTH);

        assertThat(new ItemNote(exactlyMax).value()).hasSize(ItemNote.MAX_LENGTH);
    }
}
