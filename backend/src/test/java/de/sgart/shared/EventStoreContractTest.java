package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.shared.support.CounterAggregate;
import de.sgart.shared.support.CounterAggregate.Increment;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * {@link EventStore} port contract (expected-version optimistic concurrency + {@code commandId}
 * idempotent replay) through the {@link InMemoryEventStore} double and the synthetic
 * {@link CounterAggregate} (AC2, AC3).
 */
class EventStoreContractTest {

    private InMemoryEventStore eventStore;
    private StreamId streamId;

    @BeforeEach
    void setUp() {
        eventStore = new InMemoryEventStore();
        streamId = StreamId.forHousehold(HouseholdId.generate());
    }

    @Test
    void appendingAtTheCurrentVersionAdvancesTheStreamByTheEventCount() {
        CounterAggregate counter = new CounterAggregate(streamId);
        counter.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));

        eventStore.append(AggregateVersion.initial(streamId), counter.uncommittedEvents(), CommandId.generate());

        assertThat(eventStore.readStream(streamId)).hasSize(1);
    }

    @Test
    void readStreamReturnsAppendedEventsInOrderForRehydration() {
        appendOneIncrement(AggregateVersion.initial(streamId), CommandId.generate());
        appendOneIncrement(AggregateVersion.of(streamId, 1), CommandId.generate());

        CounterAggregate rehydrated = new CounterAggregate(streamId);
        rehydrated.replay(eventStore.readStream(streamId));

        assertThat(rehydrated.count()).isEqualTo(2);
        assertThat(rehydrated.version()).isEqualTo(AggregateVersion.of(streamId, 2));
    }

    @Test
    void readStreamOnANeverAppendedStreamReturnsAnEmptyList() {
        StreamId neverAppended = StreamId.forHousehold(HouseholdId.generate());

        assertThat(eventStore.readStream(neverAppended)).isEmpty();
    }

    @Test
    void rejectsAnAppendWhoseBasedOnVersionIsBehindTheStream() {
        appendOneIncrement(AggregateVersion.initial(streamId), CommandId.generate());
        CounterAggregate stale = new CounterAggregate(streamId);
        stale.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));

        assertThatThrownBy(() ->
                        eventStore.append(
                                AggregateVersion.initial(streamId), // behind: stream is already at version 1
                                stale.uncommittedEvents(),
                                CommandId.generate()))
                .isInstanceOf(ConcurrencyConflictException.class)
                .satisfies(thrown -> assertThat(
                                ((ConcurrencyConflictException) thrown).errorDescriptor().code())
                        .isEqualTo(ConcurrencyConflictException.ERROR_CODE));

        assertThat(eventStore.readStream(streamId)).hasSize(1); // nothing was appended
    }

    @Test
    void appliesTheSameCommandIdOnlyOnce() {
        CommandId retriedCommand = CommandId.generate();
        appendOneIncrement(AggregateVersion.initial(streamId), retriedCommand);

        eventStore.append(AggregateVersion.initial(streamId), oneIncrement(), retriedCommand); // replay

        assertThat(eventStore.readStream(streamId)).hasSize(1); // applied once, not twice
    }

    @Test
    void aReplayedCommandIdNeverSurfacesAConflictEvenAfterTheStreamAdvanced() {
        CommandId firstCommand = CommandId.generate();
        appendOneIncrement(AggregateVersion.initial(streamId), firstCommand);
        appendOneIncrement(AggregateVersion.of(streamId, 1), CommandId.generate()); // stream now at version 2

        // Re-delivering the first command with its original (now stale) basedOnVersion is a no-op,
        // not a conflict (AC2).
        eventStore.append(AggregateVersion.initial(streamId), oneIncrement(), firstCommand);

        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void aProcessManagerCommandDerivedFromTheSameTriggeringEventIsAppliedExactlyOnce() {
        EventId triggeringEvent = EventId.generate();
        CommandId derived = CommandId.deterministicFrom(triggeringEvent);
        appendOneIncrement(AggregateVersion.initial(streamId), derived);

        // Re-processing the same triggering event derives the same commandId → idempotent no-op (AC3).
        CommandId derivedAgain = CommandId.deterministicFrom(triggeringEvent);
        eventStore.append(AggregateVersion.initial(streamId), oneIncrement(), derivedAgain);

        assertThat(eventStore.readStream(streamId)).hasSize(1);
    }

    @Test
    void basedOnVersionIsTheTargetRootsOwnStreamVersion() {
        appendOneIncrement(AggregateVersion.initial(streamId), CommandId.generate());

        CounterAggregate rehydrated = new CounterAggregate(streamId);
        rehydrated.replay(eventStore.readStream(streamId));
        // A command built against this root must carry the root's own version, not any other's.
        AggregateVersion basedOnVersion = rehydrated.version();
        Increment command = new Increment(CommandId.generate(), basedOnVersion);

        assertThat(command.basedOnVersion()).isEqualTo(AggregateVersion.of(streamId, 1));
        // Appending at exactly that version succeeds — proving it is the correct expected token.
        CounterAggregate next = new CounterAggregate(streamId);
        next.replay(eventStore.readStream(streamId));
        next.handle(command);
        eventStore.append(command.basedOnVersion(), next.uncommittedEvents(), command.commandId());

        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void aVersionFromADifferentStreamCanNeverBeMistakenForThisStreamsVersion() {
        appendOneIncrement(AggregateVersion.initial(streamId), CommandId.generate());

        StreamId otherStreamId = StreamId.forHousehold(HouseholdId.generate());
        AggregateVersion otherAggregatesVersion = new CounterAggregate(otherStreamId).version();

        // A handler that mistakenly reuses a DIFFERENT aggregate's version as basedOnVersion cannot
        // silently corrupt this stream: the stream an append targets is derived from the version
        // itself, so the two can never disagree (AD-8: "never a related aggregate's version"). The
        // mistaken append lands on the OTHER aggregate's own stream instead — this stream is
        // untouched.
        eventStore.append(otherAggregatesVersion, oneIncrement(), CommandId.generate());

        assertThat(eventStore.readStream(streamId)).hasSize(1); // this stream: untouched
        assertThat(eventStore.readStream(otherStreamId)).hasSize(1); // landed on the other stream
    }

    @Test
    void appendingMultipleEventsInOneCommandLandsThemAllTogether() {
        CounterAggregate counter = new CounterAggregate(streamId);
        counter.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));
        counter.handle(new Increment(CommandId.generate(), AggregateVersion.of(streamId, 1)));
        List<DomainEvent> twoEvents = counter.uncommittedEvents();

        eventStore.append(AggregateVersion.initial(streamId), twoEvents, CommandId.generate());

        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void rejectsAMultiEventAppendAtomicallyLeavingNothingWritten() {
        CounterAggregate counter = new CounterAggregate(streamId);
        counter.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));
        counter.handle(new Increment(CommandId.generate(), AggregateVersion.of(streamId, 1)));
        List<DomainEvent> twoEvents = counter.uncommittedEvents();

        appendOneIncrement(AggregateVersion.initial(streamId), CommandId.generate()); // stream now at version 1

        assertThatThrownBy(() ->
                        eventStore.append(
                                AggregateVersion.initial(streamId), // behind: stream is already at version 1
                                twoEvents,
                                CommandId.generate()))
                .isInstanceOf(ConcurrencyConflictException.class);

        assertThat(eventStore.readStream(streamId)).hasSize(1); // only the earlier single append landed
    }

    private void appendOneIncrement(AggregateVersion expectedVersion, CommandId commandId) {
        eventStore.append(expectedVersion, oneIncrement(), commandId);
    }

    private List<DomainEvent> oneIncrement() {
        CounterAggregate counter = new CounterAggregate(streamId);
        counter.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));
        return counter.uncommittedEvents();
    }
}
