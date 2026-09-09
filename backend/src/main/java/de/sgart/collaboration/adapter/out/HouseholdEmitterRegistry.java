package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.LiveConnection;
import de.sgart.collaboration.application.LiveConnectionRegistry;
import de.sgart.collaboration.application.exception.LiveConnectionClosedException;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The in-memory live-sync connection registry (Story 4.4, T3) implementing the {@link
 * LiveConnectionRegistry} port: {@code household -> member -> a set of connections} (a {@code Set}
 * per member so each of a person's devices gets its own stream). Single-process assumption is fine
 * for MVP (modular monolith, one instance); horizontal scale-out would need a shared bus — out of
 * scope (deferred successor).
 *
 * <p>{@link #broadcast} fires each connection's send on {@link #sendExecutor} and never waits on it
 * (Review P7): {@code LiveConnection#sendChangedEvent} is a blocking transport call (an {@code
 * SseEmitter.send()}), and one client whose socket is wedged must never stall nudge delivery to
 * every other member/household sharing this single fan-out. The per-connection timeout/drop policy
 * lives in one place — {@link #dispatch} — parameterized by the send action, so a future heartbeat
 * sweep (currently on {@code HouseholdStreamController}, deferred) can reuse the same seam over the
 * same sinks rather than re-implement the async-timeout dance.
 */
public final class HouseholdEmitterRegistry implements LiveConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(HouseholdEmitterRegistry.class);
    private static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(5);

    private final ConcurrentMap<HouseholdId, ConcurrentMap<MemberId, Set<LiveConnection>>> byHousehold =
            new ConcurrentHashMap<>();
    private final ExecutorService sendExecutor;
    private final Duration sendTimeout;

    public HouseholdEmitterRegistry() {
        this(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "household-live-sync-send");
            thread.setDaemon(true);
            return thread;
        }), DEFAULT_SEND_TIMEOUT);
    }

    /** Test seam (CLAUDE.md §6) — lets a test inject a short timeout instead of waiting {@link #DEFAULT_SEND_TIMEOUT}. */
    HouseholdEmitterRegistry(ExecutorService sendExecutor, Duration sendTimeout) {
        this.sendExecutor = sendExecutor;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void register(HouseholdId householdId, MemberId memberId, LiveConnection connection) {
        byHousehold
                .computeIfAbsent(householdId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(memberId, ignored -> new CopyOnWriteArraySet<>())
                .add(connection);
    }

    @Override
    public void deregister(HouseholdId householdId, MemberId memberId, LiveConnection connection) {
        ConcurrentMap<MemberId, Set<LiveConnection>> members = byHousehold.get(householdId);
        if (members == null) {
            return;
        }
        members.computeIfPresent(memberId, (ignored, connections) -> {
            connections.remove(connection);
            return connections.isEmpty() ? null : connections;
        });
    }

    @Override
    public void evictMember(HouseholdId householdId, MemberId memberId) {
        ConcurrentMap<MemberId, Set<LiveConnection>> members = byHousehold.get(householdId);
        if (members == null) {
            return;
        }
        Set<LiveConnection> connections = members.remove(memberId);
        if (connections != null) {
            connections.forEach(LiveConnection::close);
        }
    }

    @Override
    public void evictHousehold(HouseholdId householdId) {
        ConcurrentMap<MemberId, Set<LiveConnection>> members = byHousehold.remove(householdId);
        if (members == null) {
            return;
        }
        members.values().forEach(connections -> connections.forEach(LiveConnection::close));
    }

    @Override
    public void broadcast(HouseholdId householdId, String resource) {
        ConcurrentMap<MemberId, Set<LiveConnection>> members = byHousehold.get(householdId);
        if (members == null) {
            return;
        }
        members.forEach((memberId, connections) -> connections.forEach(
                connection -> dispatch(householdId, memberId, connection, () -> connection.sendChangedEvent(resource))));
    }

    /**
     * Fires one connection's blocking send on {@link #sendExecutor} and returns immediately — the
     * fan-out (KurrentDB subscription) thread never waits on any client (Review P7). A send that
     * throws (dead socket) or outruns {@link #sendTimeout} (wedged socket) deregisters the
     * connection on the completing thread, off the fan-out path, so later broadcasts skip it. The
     * wedged send still parks its own pool thread until the transport itself errors — inherent to a
     * blocking {@code SseEmitter.send()}; {@link CompletableFuture#orTimeout} bounds the future, not
     * the task — but it no longer delays delivery to anyone else.
     *
     * <p>Takes the send as a {@link Runnable} rather than hard-coding {@code sendChangedEvent} so
     * the timeout/drop policy is single-sourced for every {@link LiveConnection} side-effect (the
     * deferred heartbeat sweep is the intended second caller).
     */
    private void dispatch(HouseholdId householdId, MemberId memberId, LiveConnection connection, Runnable send) {
        CompletableFuture.runAsync(send, sendExecutor)
                .orTimeout(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(failure -> {
                    dropConnection(householdId, memberId, connection, failure);
                    return null;
                });
    }

    private void dropConnection(HouseholdId householdId, MemberId memberId, LiveConnection connection, Throwable failure) {
        Throwable cause = failure instanceof CompletionException completion ? completion.getCause() : failure;
        if (cause instanceof TimeoutException) {
            log.warn("Live-sync send to member {} exceeded {} — dropping the connection", memberId, sendTimeout, cause);
        } else {
            // Never let one dead client block the others (T3) — deregister and move on.
            log.debug("Dropping dead live-sync connection for member {}", memberId, cause);
        }
        deregister(householdId, memberId, connection);
    }
}
