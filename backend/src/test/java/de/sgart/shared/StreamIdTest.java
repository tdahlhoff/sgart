package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.shared.StreamId.StreamType;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6).
 */
class StreamIdTest {

    @Test
    void forHousehold_encodesTheHouseholdStreamKeyConvention() {
        HouseholdId householdId = HouseholdId.generate();

        StreamId streamId = StreamId.forHousehold(householdId);

        assertThat(streamId.type()).isEqualTo(StreamType.HOUSEHOLD);
        assertThat(streamId.key()).isEqualTo("household-" + householdId);
        assertThat(streamId).hasToString("household-" + householdId);
    }

    @Test
    void forHousehold_rejectsANullHouseholdId() {
        assertThatThrownBy(() -> StreamId.forHousehold(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void streamTypePrefixes_followTheSpineConvention() {
        assertThat(StreamType.HOUSEHOLD.prefix()).isEqualTo("household");
        assertThat(StreamType.LIST.prefix()).isEqualTo("list");
        assertThat(StreamType.TRIP.prefix()).isEqualTo("trip");
    }

    @Test
    void equals_treatsTwoKeysForTheSameStreamAsEqual() {
        HouseholdId householdId = HouseholdId.generate();

        assertThat(StreamId.forHousehold(householdId))
                .isEqualTo(StreamId.forHousehold(householdId));
    }
}
