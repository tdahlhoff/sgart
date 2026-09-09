package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.LiveConnection;
import de.sgart.collaboration.application.exception.LiveConnectionClosedException;
import de.sgart.shared.HouseholdId;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The SSE transport's {@link LiveConnection} implementation (Story 4.4, T1/T3/T5) — the only place
 * that knows the SSE wire format. Builds the content-free {@code changed} nudge frame itself (LD-1:
 * {@code {"householdId":"…","resource":"…"}}, never item/list/trip/member content) and
 * <strong>serializes every send</strong> behind one lock: the periodic heartbeat (T1) and the
 * live-sync fan-out's broadcast (T2) are two independent threads that can call this object at the
 * same time, and {@code SseEmitter.send()} is not safe for concurrent calls.
 */
final class SseHouseholdConnection implements LiveConnection {

    private final SseEmitter emitter;
    private final String householdId;
    private final Object sendLock = new Object();
    private volatile boolean closed;

    SseHouseholdConnection(SseEmitter emitter, HouseholdId householdId) {
        this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
        this.householdId = Objects.requireNonNull(householdId, "householdId must not be null").toString();
    }

    @Override
    public void sendChangedEvent(String resource) {
        send(SseEmitter.event().name("changed").data(changedEventJson(householdId, resource)));
    }

    /**
     * Builds the content-free nudge body (LD-1: {@code {"householdId":"…","resource":"…"}}) —
     * extracted as a pure function so the wire frame's exact content is unit-testable without a
     * real {@link SseEmitter} (Story 4.4, T13). Both inputs are server-controlled ({@code
     * HouseholdId#toString()} and one of the adapter.out resolver's fixed {@code resource} values,
     * never user input), so no JSON-string escaping is needed.
     */
    static String changedEventJson(String householdId, String resource) {
        return "{\"householdId\":\"" + householdId + "\",\"resource\":\"" + resource + "\"}";
    }

    @Override
    public void sendHeartbeat() {
        send(SseEmitter.event().comment(""));
    }

    private void send(SseEmitter.SseEventBuilder event) {
        synchronized (sendLock) {
            if (closed) {
                return;
            }
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException failure) {
                throw new LiveConnectionClosedException("SSE connection is no longer writable", failure);
            }
        }
    }

    @Override
    public void close() {
        synchronized (sendLock) {
            if (closed) {
                return;
            }
            closed = true;
            emitter.complete();
        }
    }
}
