package de.sgart.shared;

import java.util.List;

/**
 * Domain-owned port to the append-only event store — the only persistence collaborator a command
 * handler has (AD-1, AD-4). A handler emits events and appends them here; it never writes a read
 * model. Read models are built solely by projectors subscribed to streams (read-models-are-projection-only,
 * AD-4). The real KurrentDB adapter (Story 1.6) implements this port in {@code adapter.out}; a
 * kernel-purity ArchUnit rule keeps the port free of infrastructure types.
 */
public interface EventStore {

    /**
     * Appends {@code events} to {@code expectedVersion.streamId()} atomically — all land or none —
     * under an expected-version (optimistic-concurrency) check.
     *
     * <p>There is deliberately no separate {@code StreamId} parameter: {@link AggregateVersion}
     * carries the stream it belongs to, so the target stream and the expected version can never
     * disagree — the one value that must be true (AD-8: {@code basedOnVersion} is always the
     * target aggregate root's own stream version) is structurally the only value there is to pass.
     *
     * <p><strong>Concurrency:</strong> the append succeeds only if the stream's current version
     * equals {@code expectedVersion}; otherwise it is rejected with
     * {@link ConcurrencyConflictException} and nothing is written (AD-8).
     *
     * <p><strong>Idempotency:</strong> if {@code commandId} has already been applied to this stream,
     * the call is a silent no-op success — the events are not appended a second time and no conflict
     * is raised (AD-8). This is what lets a redelivered command (client retry, or a process manager
     * re-processing a triggering event) apply its effect exactly once. Implementations must persist
     * the applied {@code commandId} alongside the append (in KurrentDB via event metadata / an
     * idempotency key) so the dedupe survives a restart; the in-memory double mirrors this.
     *
     * @throws ConcurrencyConflictException if the stream has advanced past {@code expectedVersion}
     */
    void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId);

    /** Reads the stream's events in order, for rehydrating an aggregate. Empty if the stream is new. */
    List<DomainEvent> readStream(StreamId streamId);
}
