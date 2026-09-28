package de.sgart.identity.adapter.out;

import de.sgart.identity.application.RecoveryCodeIssuanceThrottle;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Story 8.6 in-memory {@link RecoveryCodeIssuanceThrottle}: no new DB table or migration — the
 * single-node beta suits an in-memory counter just fine (a restart resets it, and an attacker
 * cannot trigger restarts). Policy: at least {@link #COOLDOWN} between issues for one account, and
 * at most {@link #MAX_ISSUES_PER_WINDOW} issues per rolling {@link #WINDOW}. Thread-safe — every
 * read-then-write against one account's issuance history happens under a lock, and expired
 * entries are pruned lazily on the account's next check (no background task).
 */
public final class InMemoryRecoveryCodeIssuanceThrottle implements RecoveryCodeIssuanceThrottle {

    static final Duration COOLDOWN = Duration.ofSeconds(60);
    static final Duration WINDOW = Duration.ofHours(24);
    static final int MAX_ISSUES_PER_WINDOW = 5;

    private final Clock clock;
    private final Object lock = new Object();
    private final ConcurrentHashMap<KeycloakUserId, Deque<Instant>> issuancesByAccount = new ConcurrentHashMap<>();

    public InMemoryRecoveryCodeIssuanceThrottle(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean tryIssue(KeycloakUserId targetAccount) {
        Objects.requireNonNull(targetAccount, "targetAccount must not be null");
        Instant now = clock.instant();

        synchronized (lock) {
            Deque<Instant> issuances = issuancesByAccount.computeIfAbsent(targetAccount, ignored -> new ArrayDeque<>());
            pruneExpired(issuances, now);

            if (!issuances.isEmpty() && Duration.between(issuances.peekLast(), now).compareTo(COOLDOWN) < 0) {
                return false;
            }
            if (issuances.size() >= MAX_ISSUES_PER_WINDOW) {
                return false;
            }

            issuances.addLast(now);
            return true;
        }
    }

    /** Test-only reset (mirrors the other {@code InMemory*} doubles' {@code clear()}). */
    public void clear() {
        synchronized (lock) {
            issuancesByAccount.clear();
        }
    }

    private static void pruneExpired(Deque<Instant> issuances, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!issuances.isEmpty() && !issuances.peekFirst().isAfter(cutoff)) {
            issuances.pollFirst();
        }
    }
}
