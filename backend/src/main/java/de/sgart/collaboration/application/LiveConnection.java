package de.sgart.collaboration.application;

import de.sgart.collaboration.application.exception.LiveConnectionClosedException;

/**
 * One open live-sync channel a member holds for a household (Story 4.4) — the application-layer
 * abstraction over a transport-specific push connection (an SSE {@code SseEmitter} in {@code
 * adapter.in} today). Kept framework-free so {@link LiveConnectionRegistry} (adapter.out) and the
 * live-sync fan-out never import a Spring MVC/servlet type (AD-1 in spirit, even though only the
 * domain/shared kernel are ArchUnit-enforced pure) — the transport adapter is the only place that
 * knows what an SSE frame looks like.
 *
 * <p>A single connection instance is only ever accessed from {@code adapter.in} (registration,
 * heartbeat) and the live-sync fan-out (broadcast, eviction) — both funnel every send through the
 * same instance, so an implementation that serializes concurrent sends internally (a per-emitter
 * lock) makes concurrent heartbeat-vs-broadcast writes safe without either caller knowing about it.
 */
public interface LiveConnection {

    /**
     * Pushes the content-free "something changed" nudge (LD-1) — never item/list/trip/member
     * content, only the coarse {@code resource} hint.
     *
     * @throws LiveConnectionClosedException if the underlying transport is no longer writable
     */
    void sendChangedEvent(String resource);

    /**
     * Pushes a transport-level keep-alive so idle connections aren't dropped by an intermediary
     * proxy and dead peers are detected promptly.
     *
     * @throws LiveConnectionClosedException if the underlying transport is no longer writable
     */
    void sendHeartbeat();

    /** Closes the connection server-side (the eviction path, AC3) — idempotent. */
    void close();
}
