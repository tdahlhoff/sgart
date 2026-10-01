package de.sgart.identity.adapter.out;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A generic in-memory sliding-window budget: at least {@code cooldown} between two grants for one
 * key, and at most {@code maximumPerWindow} grants per rolling {@code window}. No database table:
 * the single-node beta suits an in-memory counter (a restart resets it, and an attacker cannot
 * trigger restarts). Keys are digests or account ids only, never personal data. Thread-safe, and
 * expired entries are pruned lazily on the key's next check.
 *
 * @param <K> what the budget is keyed by
 */
final class InMemorySlidingWindowThrottle<K> {

    /** A zero {@code cooldown} means no minimum spacing. */
    record Policy(Duration cooldown, Duration window, int maximumPerWindow) {

        Policy {
            Objects.requireNonNull(cooldown, "cooldown must not be null");
            Objects.requireNonNull(window, "window must not be null");
            if (maximumPerWindow < 1) {
                throw new IllegalArgumentException("maximumPerWindow must be at least 1");
            }
        }
    }

    private final Clock clock;
    private final Policy policy;
    private final Object lock = new Object();
    private final ConcurrentHashMap<K, Deque<Instant>> grantsByKey = new ConcurrentHashMap<>();

    InMemorySlidingWindowThrottle(Clock clock, Policy policy) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    /** @return {@code true} and records the grant if within budget; {@code false}, without side effect, otherwise. */
    boolean tryAcquire(K key) {
        Objects.requireNonNull(key, "key must not be null");
        Instant now = clock.instant();

        synchronized (lock) {
            Deque<Instant> grants = grantsByKey.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            pruneExpired(grants, now);

            if (!grants.isEmpty() && Duration.between(grants.peekLast(), now).compareTo(policy.cooldown()) < 0) {
                return false;
            }
            if (grants.size() >= policy.maximumPerWindow()) {
                return false;
            }

            grants.addLast(now);
            return true;
        }
    }

    /** Forgets the key's history, so its next acquisition is allowed immediately. */
    void reset(K key) {
        Objects.requireNonNull(key, "key must not be null");
        grantsByKey.remove(key);
    }

    void clear() {
        synchronized (lock) {
            grantsByKey.clear();
        }
    }

    private void pruneExpired(Deque<Instant> grants, Instant now) {
        Instant cutoff = now.minus(policy.window());
        while (!grants.isEmpty() && !grants.peekFirst().isAfter(cutoff)) {
            grants.pollFirst();
        }
    }
}
