package de.sgart.collaboration.domain;

import java.util.Objects;

/**
 * An item's optional note (Story 2.3, AC1) — e.g. "Bio" for milk. Not personal data (like {@link
 * ItemName}).
 *
 * <p>Trimmed, non-blank-when-present, and bounded ({@link #MAX_LENGTH}) at construction — fail
 * fast, mirroring {@link ItemName}. An <em>absent</em> note is represented by a {@code null}
 * reference, not an instance of this type — the same nullable-by-absence convention {@link
 * ShoppingListName} uses for an unnamed list. An absent note is a distinct dedup key from any
 * present note (AC2).
 */
public record ItemNote(String value) {

    public static final int MAX_LENGTH = 240;

    public ItemNote {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Item note must not be blank when present");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Item note must not exceed " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
