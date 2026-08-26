package de.sgart.collaboration.domain;

import java.util.Objects;

/**
 * A shopping list's optional display name (Story 2.1, AC1). Not personal data — it names a shared
 * list, not a person (like {@link HouseholdName}).
 *
 * <p>Trimmed, non-blank, and bounded ({@link #MAX_LENGTH}) at construction — fail fast (CLAUDE.md
 * §1), mirroring {@link HouseholdName}'s convention verbatim: a domain value object throws a plain
 * {@link IllegalArgumentException} for its own invariant, never a custom infrastructure-facing
 * type (that translation to a client-localizable {@code code} is the application layer's job,
 * keeping the domain free of any outward dependency, AD-1). An <em>absent</em> name is represented
 * by a {@code null} reference, not an instance of this type — a blank/absent name creates a valid
 * unnamed list (AC1, AC2), never an error.
 */
public record ShoppingListName(String value) {

    public static final int MAX_LENGTH = 120;

    public ShoppingListName {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Shopping list name must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Shopping list name must not exceed " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
