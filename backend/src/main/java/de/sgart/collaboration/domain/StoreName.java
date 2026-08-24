package de.sgart.collaboration.domain;

import java.util.Objects;

/**
 * A store's free-form display name (AC1). Not personal data — it names a shop, not a person; the
 * household's members are represented solely by the pseudonymous {@link de.sgart.shared.MemberId}.
 *
 * <p>Trimmed, non-blank, and bounded ({@link #MAX_LENGTH}) at construction — fail fast (CLAUDE.md
 * §1), exactly like {@link HouseholdName}: a domain value object throws a plain
 * {@link IllegalArgumentException} for its own invariant, never a custom infrastructure-facing
 * type (that translation to a client-localizable {@code code} is the application layer's job,
 * keeping the domain free of any outward dependency, AD-1). {@link #MAX_LENGTH} matches
 * {@code HouseholdName} and the {@code store_read_model.name} column bound.
 */
public record StoreName(String value) {

    public static final int MAX_LENGTH = 120;

    public StoreName {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Store name must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Store name must not exceed " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
