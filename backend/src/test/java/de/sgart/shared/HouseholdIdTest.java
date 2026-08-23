package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6).
 */
class HouseholdIdTest {

    @Test
    void generate_producesADistinctIdEachTime() {
        assertThat(HouseholdId.generate()).isNotEqualTo(HouseholdId.generate());
    }

    @Test
    void fromString_roundTripsThroughToString() {
        HouseholdId householdId = HouseholdId.generate();

        assertThat(HouseholdId.fromString(householdId.toString())).isEqualTo(householdId);
    }

    @Test
    void constructor_rejectsANullValue() {
        assertThatThrownBy(() -> new HouseholdId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equals_treatsTwoIdsWithTheSameValueAsEqual() {
        UUID value = UUID.randomUUID();

        assertThat(new HouseholdId(value)).isEqualTo(new HouseholdId(value));
    }
}
