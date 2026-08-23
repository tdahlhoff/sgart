package de.sgart.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reusable base for every aggregate root. Pure event-sourcing machinery — no business logic — so a
 * concrete aggregate (Story 1.6's {@code Household} onward) is a straight subclass.
 *
 * <p>The extension contract:
 * <ul>
 *   <li>Pass the aggregate's own {@link StreamId} to the constructor — every
 *       {@link AggregateVersion} this instance ever reports is tied to that one stream (AD-8).</li>
 *   <li>Implement {@link #apply(DomainEvent)} to mutate state from an event. <strong>State is
 *       mutated only here.</strong> Both replaying history and handling a live command route through
 *       {@code apply}, so the two take the identical path — the invariant that makes event sourcing
 *       correct.</li>
 *   <li>In a command method, validate the invariant and call {@link #raise(DomainEvent)} for each
 *       resulting event. {@code raise} applies the event and records it as uncommitted.</li>
 *   <li>Rehydrate a freshly constructed aggregate with {@link #replay(List)}, once, before any
 *       {@link #raise(DomainEvent)} call.</li>
 *   <li>The infrastructure appends {@link #uncommittedEvents()} and then calls
 *       {@link #markEventsCommitted()}.</li>
 * </ul>
 * The {@link #version()} tracks the stream version (event count), starting at
 * {@link AggregateVersion#initial(StreamId)} and advancing one step per applied event.
 */
public abstract class EventSourcedAggregate {

    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();
    private AggregateVersion version;

    protected EventSourcedAggregate(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        this.version = AggregateVersion.initial(streamId);
    }

    /** Mutates aggregate state from a single event. The only place state changes. */
    protected abstract void apply(DomainEvent event);

    /**
     * Rehydrates this freshly constructed aggregate by folding an ordered event history: each event
     * is applied and the version advances. Events replayed this way are history, not uncommitted
     * changes.
     *
     * @throws IllegalStateException if this aggregate already has uncommitted events or a
     *     non-initial version — replay is only valid on a fresh aggregate, otherwise the tracked
     *     version and rebuilt state would silently corrupt
     */
    public final void replay(List<? extends DomainEvent> history) {
        Objects.requireNonNull(history, "history must not be null");
        if (!uncommittedEvents.isEmpty() || !version.isInitial()) {
            throw new IllegalStateException(
                    "replay() is only valid on a fresh aggregate; this aggregate is already at "
                            + "version " + version + " with " + uncommittedEvents.size()
                            + " uncommitted event(s)");
        }
        for (DomainEvent event : history) {
            Objects.requireNonNull(event, "history must not contain a null event");
            applyAndAdvance(event);
        }
    }

    /** Records a new event produced by a command: applies it and adds it to the uncommitted list. */
    protected final void raise(DomainEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        applyAndAdvance(event);
        uncommittedEvents.add(event);
    }

    public final AggregateVersion version() {
        return version;
    }

    /** The events raised since the last commit, in the order they were raised. */
    public final List<DomainEvent> uncommittedEvents() {
        return List.copyOf(uncommittedEvents);
    }

    public final void markEventsCommitted() {
        uncommittedEvents.clear();
    }

    private void applyAndAdvance(DomainEvent event) {
        apply(event);
        version = version.next();
    }
}
