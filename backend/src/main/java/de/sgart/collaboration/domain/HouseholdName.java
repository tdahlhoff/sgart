package de.sgart.collaboration.domain;

import java.util.Objects;

/**
 * A household's display name. Not personal data — it names the shared tenant, not a person; the
 * creator's identity is represented solely by the pseudonymous {@link de.sgart.shared.MemberId}
 * (AC1).
 *
 * <p>Trimmed, non-blank, and bounded ({@link #MAX_LENGTH}) at construction — fail fast (CLAUDE.md
 * §1), matching {@link de.sgart.shared.Money}'s convention: a domain value object throws a plain
 * {@link IllegalArgumentException} for its own invariant, never a custom infrastructure-facing
 * type (that translation to a client-localizable {@code code} is the application layer's job,
 * keeping the domain free of any outward dependency, AD-1).
 */
public record HouseholdName(String value) {

    public static final int MAX_LENGTH = 120;

    public HouseholdName {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Household name must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Household name must not exceed " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
