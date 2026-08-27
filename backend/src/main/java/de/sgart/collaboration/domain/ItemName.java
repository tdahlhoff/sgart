package de.sgart.collaboration.domain;

import java.util.Objects;

/**
 * An item's name (Story 2.3, AC1). Not personal data — it names a grocery item, not a person (like
 * {@link ShoppingListName}).
 *
 * <p>Trimmed, non-blank, and bounded ({@link #MAX_LENGTH}) at construction — fail fast (CLAUDE.md
 * §1), mirroring {@link ShoppingListName}'s convention verbatim: a domain value object throws a
 * plain {@link IllegalArgumentException} for its own invariant, never a custom infrastructure-
 * facing type (that translation to a client-localizable {@code code} is the application layer's
 * job, keeping the domain free of any outward dependency, AD-1). Unlike {@link ShoppingListName},
 * an item's name is <strong>required</strong> — no nullable convention (AC1).
 */
public record ItemName(String value) {

    public static final int MAX_LENGTH = 120;

    public ItemName {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Item name must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Item name must not exceed " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
