package de.sgart.shared.support;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.StreamId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-only {@link EventStore} double that honours the full port contract: expected-version
 * optimistic concurrency and {@code commandId} idempotent replay. It records applied command ids
 * per stream exactly as the eventual KurrentDB adapter will via event metadata (Story 1.6), so a
 * test here proves the same behaviour the real store must deliver. No production caller of the port
 * exists until 1.6 wires the durable adapter, so this stays in test support.
 *
 * <p>{@code append} is {@code synchronized} so the expected-version check and the idempotency
 * check-then-record are each atomic with respect to concurrent callers — matching what "atomic
 * append" and "idempotent no-op" mean in the port's contract.
 */
public final class InMemoryEventStore implements EventStore {

    private final Map<StreamId, List<DomainEvent>> streams = new HashMap<>();
    private final Map<StreamId, Set<CommandId>> appliedCommandIds = new HashMap<>();

    @Override
    public synchronized void append(
            AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
        StreamId streamId = expectedVersion.streamId();
        if (appliedCommandIds.getOrDefault(streamId, Set.of()).contains(commandId)) {
            return; // idempotent replay: already applied → silent no-op, never a conflict
        }

        AggregateVersion currentVersion = currentVersionOf(streamId);
        if (!currentVersion.equals(expectedVersion)) {
            throw new ConcurrencyConflictException(expectedVersion, currentVersion);
        }

        streams.computeIfAbsent(streamId, key -> new ArrayList<>()).addAll(events);
        appliedCommandIds.computeIfAbsent(streamId, key -> new HashSet<>()).add(commandId);
    }

    @Override
    public synchronized List<DomainEvent> readStream(StreamId streamId) {
        return List.copyOf(streams.getOrDefault(streamId, List.of()));
    }

    private AggregateVersion currentVersionOf(StreamId streamId) {
        return AggregateVersion.of(streamId, streams.getOrDefault(streamId, List.of()).size());
    }
}
