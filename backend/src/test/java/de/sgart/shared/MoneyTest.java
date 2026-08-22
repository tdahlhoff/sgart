package de.sgart.shared;

import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * domain-first testing substrate the whole codebase builds on.
 */
class MoneyTest {

    @Test
    void euroFactory_producesAmountInEuroMinorUnits() {
        Money oneEuroNine = Money.euro(109);

        assertThat(oneEuroNine.amountMinor()).isEqualTo(109);
        assertThat(oneEuroNine.currency()).isEqualTo(Currency.getInstance("EUR"));
    }

    @Test
    void add_sumsAmountsOfTheSameCurrency() {
        Money total = Money.euro(150).add(Money.euro(99));

        assertThat(total).isEqualTo(Money.euro(249));
    }

    @Test
    void add_rejectsAmountsInDifferentCurrencies() {
        Money euros = Money.euro(100);
        Money dollars = new Money(100, Currency.getInstance("USD"));

        assertThatThrownBy(() -> euros.add(dollars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different currencies");
    }
}
