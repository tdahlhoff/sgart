package de.sgart.collaboration.application.exception;

import de.sgart.collaboration.application.LiveConnection;

/**
 * Raised by a {@link LiveConnection} when its underlying transport is no longer writable (the peer
 * disconnected, the transport threw on send). Never a client-facing error (Story 4.4) — the
 * registry that catches it simply deregisters the dead connection and continues broadcasting to
 * the others (T3, "one dead client must never block the others").
 */
public final class LiveConnectionClosedException extends RuntimeException {

    public LiveConnectionClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
