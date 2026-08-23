package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6).
 */
class MemberIdTest {

    @Test
    void generate_producesADistinctIdEachTime() {
        assertThat(MemberId.generate()).isNotEqualTo(MemberId.generate());
    }

    @Test
    void fromString_roundTripsThroughToString() {
        MemberId memberId = MemberId.generate();

        assertThat(MemberId.fromString(memberId.toString())).isEqualTo(memberId);
    }

    @Test
    void constructor_rejectsANullValue() {
        assertThatThrownBy(() -> new MemberId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equals_treatsTwoIdsWithTheSameValueAsEqual() {
        UUID value = UUID.randomUUID();

        assertThat(new MemberId(value)).isEqualTo(new MemberId(value));
    }
}
