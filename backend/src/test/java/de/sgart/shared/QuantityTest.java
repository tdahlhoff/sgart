package de.sgart.shared;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure domain-layer unit test for the {@link Quantity} value object (AD-9). */
class QuantityTest {

    @Test
    void of_buildsAQuantityFromAControlledUnit() {
        Quantity twoLitres = Quantity.of(2, Unit.LITRE);

        assertThat(twoLitres.amount()).isEqualByComparingTo(BigDecimal.valueOf(2));
        assertThat(twoLitres.unit()).isEqualTo(Unit.LITRE);
    }

    @Test
    void construction_rejectsANonPositiveAmount() {
        assertThatThrownBy(() -> Quantity.of(0, Unit.PIECE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
