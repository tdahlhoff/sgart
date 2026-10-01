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
 * expired grants are pruned on the key's next check, and once per window every key whose grants have all expired is dropped.
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
    private Instant nextSweepAt = Instant.MIN;

    InMemorySlidingWindowThrottle(Clock clock, Policy policy) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    /** @return {@code true} and records the grant if within budget; {@code false}, without side effect, otherwise. */
    boolean tryAcquire(K key) {
        Objects.requireNonNull(key, "key must not be null");
        Instant now = clock.instant();

        synchronized (lock) {
            removeKeysWithoutRecentGrants(now);
            Deque<Instant> grants = grantsByKey.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            pruneExpired(grants, now);

            boolean isGranted = isWithinBudget(grants, now);
            if (isGranted) {
                grants.addLast(now);
            }
            return isGranted;
        }
    }

    /** Forgets the key's history, so its next acquisition is allowed immediately. */
    void reset(K key) {
        Objects.requireNonNull(key, "key must not be null");
        synchronized (lock) {
            grantsByKey.remove(key);
        }
    }

    /** The number of keys that still hold grants; an expired key must not linger in memory. */
    int trackedKeyCount() {
        return grantsByKey.size();
    }

    void clear() {
        synchronized (lock) {
            grantsByKey.clear();
        }
    }

    /**
     * Once per window, drops every key whose grants have all expired, so keys that are never asked
     * about again (digests are attacker-chosen) cannot accumulate. Runs under {@link #lock}.
     */
    private void removeKeysWithoutRecentGrants(Instant now) {
        if (now.isBefore(nextSweepAt)) {
            return;
        }
        nextSweepAt = now.plus(policy.window());
        grantsByKey.values().forEach(grants -> pruneExpired(grants, now));
        grantsByKey.values().removeIf(Deque::isEmpty);
    }

    private boolean isWithinBudget(Deque<Instant> grants, Instant now) {
        boolean isCoolingDown =
                !grants.isEmpty() && Duration.between(grants.peekLast(), now).compareTo(policy.cooldown()) < 0;
        return !isCoolingDown && grants.size() < policy.maximumPerWindow();
    }

    private void pruneExpired(Deque<Instant> grants, Instant now) {
        Instant cutoff = now.minus(policy.window());
        while (!grants.isEmpty() && !grants.peekFirst().isAfter(cutoff)) {
            grants.pollFirst();
        }
    }
}
