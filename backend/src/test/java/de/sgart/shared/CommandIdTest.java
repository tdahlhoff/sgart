package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Pins the
 * deterministic derivation so a refactor cannot silently change it and break exactly-once (AD-10).
 */
class CommandIdTest {

    @Test
    void generate_producesADistinctIdEachTime() {
        assertThat(CommandId.generate()).isNotEqualTo(CommandId.generate());
    }

    @Test
    void fromString_roundTripsThroughToString() {
        CommandId commandId = CommandId.generate();

        assertThat(CommandId.fromString(commandId.toString())).isEqualTo(commandId);
    }

    @Test
    void constructor_rejectsANullValue() {
        assertThatThrownBy(() -> new CommandId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void deterministicFrom_derivesTheSameCommandIdFromTheSameTriggeringEvent() {
        EventId triggeringEvent = EventId.generate();

        assertThat(CommandId.deterministicFrom(triggeringEvent))
                .isEqualTo(CommandId.deterministicFrom(triggeringEvent));
    }

    @Test
    void deterministicFrom_derivesDistinctCommandIdsFromDifferentTriggeringEvents() {
        assertThat(CommandId.deterministicFrom(EventId.generate()))
                .isNotEqualTo(CommandId.deterministicFrom(EventId.generate()));
    }

    @Test
    void deterministicFrom_isPinnedToAStableDerivationForAKnownEventId() {
        EventId knownEvent = EventId.fromString("11111111-1111-1111-1111-111111111111");

        assertThat(CommandId.deterministicFrom(knownEvent).toString())
                .isEqualTo("9dfed3d1-9ae0-5191-adb4-31f7f93ecae8");
    }

    @Test
    void deterministicFrom_rejectsANullEventId() {
        assertThatThrownBy(() -> CommandId.deterministicFrom(null))
                .isInstanceOf(NullPointerException.class);
    }
}
