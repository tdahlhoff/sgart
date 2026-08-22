package de.sgart.shared;

import java.util.Currency;
import java.util.Objects;

/**
 * Money as integer minor units plus an ISO currency — never a floating-point primitive (AD-9).
 *
 * <p>The MVP is EUR-only, but currency is always explicit so a second currency never means a silent
 * reinterpretation of existing amounts. Arithmetic stays in integer minor units; there is
 * deliberately no {@code double}-based operation.
 */
public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money euro(long amountMinor) {
        return new Money(amountMinor, Currency.getInstance("EUR"));
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine amounts in different currencies: %s and %s"
                            .formatted(currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }
}
