package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailDigest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Proves the three recovery-email budgets are configured as decided and stay independent of one another. */
class InMemoryRecoveryEmailThrottlesTest {

    private static final RecoveryEmailDigest DIGEST = new RecoveryEmailDigest("digest");
    private static final KeycloakUserId CALLER = new KeycloakUserId("caller");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T10:00:00Z"));
    private final InMemoryRecoveryEmailThrottles throttles = new InMemoryRecoveryEmailThrottles(clock);

    @Test
    void tryMail_allowsThreeMailsPerAddressBackToBackAndDeniesTheFourth() {
        assertThat(throttles.tryMail(DIGEST)).isTrue();
        assertThat(throttles.tryMail(DIGEST)).isTrue();
        assertThat(throttles.tryMail(DIGEST)).isTrue();
        assertThat(throttles.tryMail(DIGEST)).isFalse();
    }

    @Test
    void tryAttach_secondAttachWithinTheCooldown_isDenied() {
        assertThat(throttles.tryAttach(CALLER)).isTrue();

        assertThat(throttles.tryAttach(CALLER)).isFalse();
    }

    @Test
    void tryRequest_secondRequestWithinTheCooldown_isDeniedUntilTheBudgetIsReset() {
        assertThat(throttles.tryRequest(DIGEST)).isTrue();
        assertThat(throttles.tryRequest(DIGEST)).isFalse();

        throttles.reset(DIGEST);

        assertThat(throttles.tryRequest(DIGEST)).isTrue();
    }

    @Test
    void tryRequest_allowsTenRequestsPerAddressInADayAndDeniesTheEleventh() {
        for (int request = 0; request < 10; request++) {
            assertThat(throttles.tryRequest(DIGEST)).isTrue();
            clock.advance(Duration.ofSeconds(60));
        }

        assertThat(throttles.tryRequest(DIGEST)).isFalse();
    }

    @Test
    void theBudgets_doNotShareAnyState() {
        while (throttles.tryMail(DIGEST)) {
            // exhaust the attach mail budget of the address
        }

        assertThat(throttles.tryRequest(DIGEST)).isTrue();
    }
}
