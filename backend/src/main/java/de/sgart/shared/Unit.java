package de.sgart.shared;

/**
 * Controlled, extensible vocabulary of measurement units (AD-9).
 *
 * <p>Free-text units are rejected by construction: a {@link Quantity} can only carry a value from
 * this enum. New units are added here deliberately, never invented at the call site.
 */
public enum Unit {
    PIECE,
    GRAM,
    KILOGRAM,
    MILLILITRE,
    LITRE,
    PACK
}
