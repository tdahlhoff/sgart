package de.sgart.keycloak.authenticator;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The small, bounded server-side replay guard (Story 7.1, D-B): remembers which {@code
 * (username, nonce)} pairs have already been used within the challenge window, so a captured
 * signed challenge cannot be replayed. Bounded by construction — a nonce only needs to be
 * remembered for the window it could still be replayed in, so entries older than twice that window
 * are evicted on every call (a nonce outside the window is already rejected on its timestamp alone,
 * so the cache never needs to grow without limit).
 */
final class NonceSeenCache {

    private final Duration window;
    private final Map<String, Instant> seenAt = new ConcurrentHashMap<>();

    NonceSeenCache(Duration window) {
        this.window = window;
    }

    /**
     * @return {@code true} and records the pair, if {@code (username, nonce)} had not been seen
     *     before; {@code false} (a replay) if it had.
     */
    boolean recordIfUnseen(String username, String nonce, Instant now) {
        evictExpired(now);
        String key = username + ':' + nonce;
        return seenAt.putIfAbsent(key, now) == null;
    }

    private void evictExpired(Instant now) {
        Duration retentionBeyondWindow = window.multipliedBy(2);
        seenAt.entrySet().removeIf(entry -> Duration.between(entry.getValue(), now).compareTo(retentionBeyondWindow) > 0);
    }
}
