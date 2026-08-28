package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6).
 */
class TripIdTest {

    @Test
    void generate_createsARandomTripId() {
        TripId tripId = TripId.generate();

        assertThat(tripId.value()).isNotNull();
    }

    @Test
    void fromString_parsesAUuidString() {
        UUID value = UUID.randomUUID();

        TripId tripId = TripId.fromString(value.toString());

        assertThat(tripId.value()).isEqualTo(value);
    }

    @Test
    void toString_returnsTheRawUuidString() {
        UUID value = UUID.randomUUID();

        TripId tripId = new TripId(value);

        assertThat(tripId).hasToString(value.toString());
    }

    @Test
    void constructor_rejectsANullValue() {
        assertThatThrownBy(() -> new TripId(null)).isInstanceOf(NullPointerException.class);
    }
}
