package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6).
 */
class EventIdTest {

    @Test
    void generate_producesADistinctIdEachTime() {
        assertThat(EventId.generate()).isNotEqualTo(EventId.generate());
    }

    @Test
    void fromString_roundTripsThroughToString() {
        EventId eventId = EventId.generate();

        assertThat(EventId.fromString(eventId.toString())).isEqualTo(eventId);
    }

    @Test
    void constructor_rejectsANullValue() {
        assertThatThrownBy(() -> new EventId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equals_treatsTwoIdsWithTheSameValueAsEqual() {
        UUID value = UUID.randomUUID();

        assertThat(new EventId(value)).isEqualTo(new EventId(value));
    }
}
