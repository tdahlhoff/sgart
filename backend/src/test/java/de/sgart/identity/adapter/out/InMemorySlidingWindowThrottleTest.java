package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
