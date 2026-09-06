package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6).
 */
class InviteIdTest {

    @Test
    void generate_producesADistinctIdEachTime() {
        assertThat(InviteId.generate()).isNotEqualTo(InviteId.generate());
    }

    @Test
    void fromString_roundTripsThroughToString() {
        InviteId inviteId = InviteId.generate();

        assertThat(InviteId.fromString(inviteId.toString())).isEqualTo(inviteId);
    }

    @Test
    void constructor_rejectsANullValue() {
        assertThatThrownBy(() -> new InviteId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equals_treatsTwoIdsWithTheSameValueAsEqual() {
        UUID value = UUID.randomUUID();

        assertThat(new InviteId(value)).isEqualTo(new InviteId(value));
    }
}
