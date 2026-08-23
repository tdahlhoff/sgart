package de.sgart.shared.support;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventSourcedAggregate;
import de.sgart.shared.StreamId;

/**
 * Synthetic reference aggregate — a test-only fixture, never production code. It proves the
 * {@link EventSourcedAggregate} contract with the smallest possible aggregate: a counter whose only
 * command, {@link Increment}, emits one {@link Incremented} event. The first real aggregate is Story
 * 1.6's {@code Household} (YAGNI: no throwaway domain concept enters {@code main}).
 */
public final class CounterAggregate extends EventSourcedAggregate {

    private int count;

    public CounterAggregate(StreamId streamId) {
        super(streamId);
    }

    /**
     * @throws IllegalStateException if {@code command}'s {@code basedOnVersion} does not match this
     *     aggregate's current version — the command was not built against the state this instance
     *     actually holds
     */
    public void handle(Increment command) {
        if (!command.basedOnVersion().equals(version())) {
            throw new IllegalStateException(
                    "Increment.basedOnVersion %s does not match the aggregate's current version %s"
                            .formatted(command.basedOnVersion(), version()));
        }
        raise(new Incremented(EventId.generate()));
    }

    public int count() {
        return count;
    }

    @Override
    protected void apply(DomainEvent event) {
        if (event instanceof Incremented) {
            count++;
        }
    }

    /** Imperative command to bump the counter; a client-originated envelope (AD-8). */
    public record Increment(CommandId commandId, AggregateVersion basedOnVersion) implements Command {}

    /** Past-tense fact that the counter was bumped. */
    public record Incremented(EventId eventId) implements DomainEvent {}
}
