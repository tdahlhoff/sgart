package de.sgart.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.shared.support.CounterAggregate;
import de.sgart.shared.support.CounterAggregate.Increment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * event-sourcing machinery through the synthetic {@link CounterAggregate}: command → event emission,
 * replay rebuilding identical state, and version tracking (AC1).
 */
class EventSourcedAggregateTest {

    private StreamId streamId;

    @BeforeEach
    void setUp() {
        streamId = StreamId.forHousehold(HouseholdId.generate());
    }

    @Test
    void aFreshAggregateStartsAtTheNewStreamSentinelVersion() {
        CounterAggregate counter = new CounterAggregate(streamId);

        assertThat(counter.version()).isEqualTo(AggregateVersion.initial(streamId));
        assertThat(counter.count()).isZero();
    }

    @Test
    void handlingACommandRaisesExactlyOneEventAndAdvancesTheVersion() {
        CounterAggregate counter = new CounterAggregate(streamId);

        counter.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));

        assertThat(counter.uncommittedEvents()).hasSize(1);
        assertThat(counter.uncommittedEvents().getFirst())
                .isInstanceOf(CounterAggregate.Incremented.class);
        assertThat(counter.version()).isEqualTo(AggregateVersion.of(streamId, 1));
        assertThat(counter.count()).isEqualTo(1);
    }

    @Test
    void rebuildsAggregateStateByReplayingItsEvents() {
        CounterAggregate original = new CounterAggregate(streamId);
        original.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));
        original.handle(new Increment(CommandId.generate(), AggregateVersion.of(streamId, 1)));
        List<DomainEvent> history = original.uncommittedEvents();

        CounterAggregate rehydrated = new CounterAggregate(streamId);
        rehydrated.replay(history);

        assertThat(rehydrated.count()).isEqualTo(original.count());
        assertThat(rehydrated.version()).isEqualTo(original.version());
        assertThat(rehydrated.version()).isEqualTo(AggregateVersion.of(streamId, 2));
    }

    @Test
    void replayedEventsAreHistoryNotUncommittedChanges() {
        CounterAggregate source = new CounterAggregate(streamId);
        source.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));

        CounterAggregate rehydrated = new CounterAggregate(streamId);
        rehydrated.replay(source.uncommittedEvents());

        assertThat(rehydrated.uncommittedEvents()).isEmpty();
    }

    @Test
    void markEventsCommittedClearsTheUncommittedEvents() {
        CounterAggregate counter = new CounterAggregate(streamId);
        counter.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));

        counter.markEventsCommitted();

        assertThat(counter.uncommittedEvents()).isEmpty();
        assertThat(counter.version()).isEqualTo(AggregateVersion.of(streamId, 1));
    }

    @Test
    void replayRejectsBeingCalledTwiceBecauseTheAggregateIsNoLongerFresh() {
        CounterAggregate counter = new CounterAggregate(streamId);
        counter.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));
        List<DomainEvent> history = counter.uncommittedEvents();

        CounterAggregate rehydrated = new CounterAggregate(streamId);
        rehydrated.replay(history);

        assertThatThrownBy(() -> rehydrated.replay(history))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replayRejectsBeingCalledAfterRaiseBecauseTheAggregateIsNoLongerFresh() {
        CounterAggregate counter = new CounterAggregate(streamId);
        counter.handle(new Increment(CommandId.generate(), AggregateVersion.initial(streamId)));

        assertThatThrownBy(() -> counter.replay(List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replayRejectsAHistoryContainingANullEvent() {
        CounterAggregate counter = new CounterAggregate(streamId);
        List<DomainEvent> historyWithNull = new ArrayList<>();
        historyWithNull.add(null);

        assertThatThrownBy(() -> counter.replay(historyWithNull))
                .isInstanceOf(NullPointerException.class);
    }
}
