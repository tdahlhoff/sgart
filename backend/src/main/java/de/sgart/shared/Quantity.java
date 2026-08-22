package de.sgart.shared;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An amount paired with a {@link Unit} from the controlled vocabulary (AD-9).
 *
 * <p>{@link BigDecimal} keeps fractional amounts (0.5 kg) exact; the unit is never free text.
 * A non-positive amount is rejected fail-fast — a shopping quantity of zero or less has no meaning.
 */
public record Quantity(BigDecimal amount, Unit unit) {

    public Quantity {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
    }

    public static Quantity of(long amount, Unit unit) {
        return new Quantity(BigDecimal.valueOf(amount), unit);
    }
}
