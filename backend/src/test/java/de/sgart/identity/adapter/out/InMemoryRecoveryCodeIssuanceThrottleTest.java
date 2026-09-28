package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.KeycloakUserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework (CLAUDE.md §6). Proves the Story 8.6 issuance policy: at
 * least 60s between issues for one account, at most 5 issues per rolling 24h, the window rolling
 * forward again, and isolation between accounts (D-A: detach never touches the throttle, so its
 * independence from detach needs no test here — nothing in this class calls it).
 */
class InMemoryRecoveryCodeIssuanceThrottleTest {

    private static final Instant START = Instant.parse("2026-09-28T10:00:00Z");
    private static final KeycloakUserId ACCOUNT_A = new KeycloakUserId("account-a");
    private static final KeycloakUserId ACCOUNT_B = new KeycloakUserId("account-b");

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryRecoveryCodeIssuanceThrottle throttle = new InMemoryRecoveryCodeIssuanceThrottle(clock);

    @Test
    void tryIssue_firstIssuanceForAnAccount_isAllowed() {
        assertThat(throttle.tryIssue(ACCOUNT_A)).isTrue();
    }

    @Test
    void tryIssue_secondIssuanceWithinCooldown_isDenied() {
        throttle.tryIssue(ACCOUNT_A);
        clock.advance(Duration.ofSeconds(59));

        assertThat(throttle.tryIssue(ACCOUNT_A)).isFalse();
    }

    @Test
    void tryIssue_afterCooldownElapses_isAllowedAgain() {
        throttle.tryIssue(ACCOUNT_A);
        clock.advance(Duration.ofSeconds(60));

        assertThat(throttle.tryIssue(ACCOUNT_A)).isTrue();
    }

    @Test
    void tryIssue_sixthIssuanceWithinTheRollingDay_isDenied() {
        for (int i = 0; i < 5; i++) {
            assertThat(throttle.tryIssue(ACCOUNT_A)).isTrue();
            clock.advance(Duration.ofSeconds(60));
        }

        assertThat(throttle.tryIssue(ACCOUNT_A)).isFalse();
    }

    @Test
    void tryIssue_atExactlyFirstIssuePlus24h_reopensExactlyOneSlotNotUnlimited() {
        Instant firstIssuedAt = clock.instant();
        for (int i = 0; i < 5; i++) {
            assertThat(throttle.tryIssue(ACCOUNT_A)).isTrue();
            clock.advance(Duration.ofSeconds(60));
        }
        assertThat(throttle.tryIssue(ACCOUNT_A)).isFalse();

        // Exactly 24h after the FIRST issuance in the window (not "24h from wherever we are") —
        // that one issuance rolls out of the window right at this instant, per the spec matrix
        // ("Window rolls: 24h after the first issue in the window -> issuing allowed again").
        clock.advance(Duration.between(clock.instant(), firstIssuedAt.plus(InMemoryRecoveryCodeIssuanceThrottle.WINDOW)));

        assertThat(throttle.tryIssue(ACCOUNT_A)).isTrue();
        // Only the one rolled-out slot reopened — the other four issuances are still within the
        // window, so an immediate further request is denied again.
        assertThat(throttle.tryIssue(ACCOUNT_A)).isFalse();
    }

    @Test
    void tryIssue_isIsolatedPerAccount() {
        for (int i = 0; i < 5; i++) {
            throttle.tryIssue(ACCOUNT_A);
            clock.advance(Duration.ofSeconds(60));
        }
        assertThat(throttle.tryIssue(ACCOUNT_A)).isFalse();

        // Account B's own budget is untouched by A's exhaustion.
        assertThat(throttle.tryIssue(ACCOUNT_B)).isTrue();
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
