package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.shared.support.CounterAggregate.Increment;
import de.sgart.shared.support.CounterAggregate.Incremented;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Confirms the
 * envelope contracts are usable by a concrete synthetic command/event: a {@link Command} exposes its
 * {@link CommandId} and {@link AggregateVersion}, and a {@link DomainEvent} exposes its
 * {@link EventId} (AC2).
 */
class CommandAndDomainEventContractTest {

    @Test
    void aCommandExposesItsCommandIdAndBasedOnVersion() {
        CommandId commandId = CommandId.generate();
        StreamId streamId = StreamId.forHousehold(HouseholdId.generate());
        AggregateVersion basedOnVersion = AggregateVersion.of(streamId, 4);

        Command command = new Increment(commandId, basedOnVersion);

        assertThat(command.commandId()).isEqualTo(commandId);
        assertThat(command.basedOnVersion()).isEqualTo(basedOnVersion);
    }

    @Test
    void aDomainEventExposesItsEventId() {
        EventId eventId = EventId.generate();

        DomainEvent event = new Incremented(eventId);

        assertThat(event.eventId()).isEqualTo(eventId);
    }
}
