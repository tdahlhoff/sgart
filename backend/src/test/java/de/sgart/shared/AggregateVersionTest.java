package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6).
 */
class AggregateVersionTest {

    private final StreamId streamId = StreamId.forHousehold(HouseholdId.generate());

    @Test
    void initial_isTheNewStreamSentinelMeaningNoEventsYet() {
        AggregateVersion initial = AggregateVersion.initial(streamId);

        assertThat(initial.value()).isZero();
        assertThat(initial.isInitial()).isTrue();
    }

    @Test
    void next_advancesTheVersionByOneEventAndKeepsTheSameStream() {
        AggregateVersion afterTwoEvents = AggregateVersion.initial(streamId).next().next();

        assertThat(afterTwoEvents.value()).isEqualTo(2);
        assertThat(afterTwoEvents.isInitial()).isFalse();
        assertThat(afterTwoEvents.streamId()).isEqualTo(streamId);
    }

    @Test
    void constructor_rejectsANegativeValue() {
        assertThatThrownBy(() -> new AggregateVersion(streamId, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void constructor_rejectsANullStreamId() {
        assertThatThrownBy(() -> new AggregateVersion(null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equals_treatsTwoVersionsWithTheSameStreamAndValueAsEqual() {
        assertThat(AggregateVersion.of(streamId, 3)).isEqualTo(AggregateVersion.of(streamId, 3));
    }

    @Test
    void equals_treatsTwoVersionsWithTheSameValueButDifferentStreamsAsNotEqual() {
        StreamId otherStreamId = StreamId.forHousehold(HouseholdId.generate());

        assertThat(AggregateVersion.of(streamId, 3))
                .isNotEqualTo(AggregateVersion.of(otherStreamId, 3));
    }
}
