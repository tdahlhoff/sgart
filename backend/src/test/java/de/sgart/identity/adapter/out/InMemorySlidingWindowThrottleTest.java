package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework. Proves the sliding-window policy (cooldown between grants,
 * a cap per rolling window, the window rolling forward again), isolation between keys, and reset.
 */
class InMemorySlidingWindowThrottleTest {

    private static final Instant START = Instant.parse("2026-09-28T10:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final InMemorySlidingWindowThrottle.Policy POLICY =
            new InMemorySlidingWindowThrottle.Policy(Duration.ofSeconds(60), WINDOW, 5);

    private final MutableClock clock = new MutableClock(START);
    private final InMemorySlidingWindowThrottle<String> throttle = new InMemorySlidingWindowThrottle<>(clock, POLICY);

    @Test
    void tryAcquire_firstGrantForAKey_isAllowed() {
        assertThat(throttle.tryAcquire("key-a")).isTrue();
    }

    @Test
    void tryAcquire_secondGrantWithinCooldown_isDenied() {
        throttle.tryAcquire("key-a");
        clock.advance(Duration.ofSeconds(59));

        assertThat(throttle.tryAcquire("key-a")).isFalse();
    }

    @Test
    void tryAcquire_afterCooldownElapses_isAllowedAgain() {
        throttle.tryAcquire("key-a");
        clock.advance(Duration.ofSeconds(60));

        assertThat(throttle.tryAcquire("key-a")).isTrue();
    }

    @Test
    void tryAcquire_beyondTheCapWithinTheRollingWindow_isDenied() {
        for (int grant = 0; grant < 5; grant++) {
            assertThat(throttle.tryAcquire("key-a")).isTrue();
            clock.advance(Duration.ofSeconds(60));
        }

        assertThat(throttle.tryAcquire("key-a")).isFalse();
    }

    @Test
    void tryAcquire_atExactlyFirstGrantPlusWindow_reopensExactlyOneSlot() {
        Instant firstGrantedAt = clock.instant();
        for (int grant = 0; grant < 5; grant++) {
            throttle.tryAcquire("key-a");
            clock.advance(Duration.ofSeconds(60));
        }
        assertThat(throttle.tryAcquire("key-a")).isFalse();

        clock.advance(Duration.between(clock.instant(), firstGrantedAt.plus(WINDOW)));

        assertThat(throttle.tryAcquire("key-a")).isTrue();
        assertThat(throttle.tryAcquire("key-a")).isFalse();
    }

    @Test
    void tryAcquire_isIsolatedPerKey() {
        for (int grant = 0; grant < 5; grant++) {
            throttle.tryAcquire("key-a");
            clock.advance(Duration.ofSeconds(60));
        }
        assertThat(throttle.tryAcquire("key-a")).isFalse();

        assertThat(throttle.tryAcquire("key-b")).isTrue();
    }

    @Test
    void tryAcquire_withoutACooldown_allowsBackToBackGrantsUpToTheCap() {
        InMemorySlidingWindowThrottle<String> withoutCooldown = new InMemorySlidingWindowThrottle<>(
                clock, new InMemorySlidingWindowThrottle.Policy(Duration.ZERO, WINDOW, 3));

        assertThat(withoutCooldown.tryAcquire("key-a")).isTrue();
        assertThat(withoutCooldown.tryAcquire("key-a")).isTrue();
        assertThat(withoutCooldown.tryAcquire("key-a")).isTrue();
        assertThat(withoutCooldown.tryAcquire("key-a")).isFalse();
    }

    @Test
    void reset_allowsTheNextIssuanceImmediately() {
        throttle.tryAcquire("key-a");

        throttle.reset("key-a");

        assertThat(throttle.tryAcquire("key-a")).isTrue();
    }

    @Test
    void tryAcquire_afterAKeysGrantsAllExpired_dropsThatKeyFromMemory() {
        throttle.tryAcquire("key-a");
        throttle.tryAcquire("key-b");
        clock.advance(WINDOW.plusSeconds(1));

        throttle.tryAcquire("key-c");

        assertThat(throttle.trackedKeyCount()).isEqualTo(1);
    }

    @Test
    void reset_runningConcurrentlyWithAcquisitions_neverLosesTheBudgetOfTheNextGrant() throws Exception {
        InMemorySlidingWindowThrottle<String> singleGrant = new InMemorySlidingWindowThrottle<>(
                clock, new InMemorySlidingWindowThrottle.Policy(Duration.ZERO, WINDOW, 1));
        AtomicInteger grants = new AtomicInteger();
        AtomicInteger resets = new AtomicInteger();
        int rounds = 20_000;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(3);
        List<Future<Void>> acquirers = new ArrayList<>();
        AtomicBoolean acquirersFinished = new AtomicBoolean();

        try {
            for (int acquirer = 0; acquirer < 2; acquirer++) {
                acquirers.add(threads.submit(() -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        if (singleGrant.tryAcquire("key-a")) {
                            grants.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            // Resets keep coming for as long as the acquirers run, so the budget really reopens mid-race.
            Future<Void> resetter = threads.submit(() -> {
                start.await();
                while (!acquirersFinished.get()) {
                    resets.incrementAndGet();
                    singleGrant.reset("key-a");
                }
                return null;
            });
            start.countDown();
            for (Future<Void> acquirer : acquirers) {
                acquirer.get(30, TimeUnit.SECONDS);
            }
            acquirersFinished.set(true);
            resetter.get(30, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }

        // With a budget of one, every grant after the first needs a reset in between.
        assertThat(grants.get()).isLessThanOrEqualTo(resets.get() + 1);
        // Guards against a vacuous pass: the budget did reopen, so more than one grant happened.
        assertThat(grants.get()).isGreaterThan(1);
    }
}
