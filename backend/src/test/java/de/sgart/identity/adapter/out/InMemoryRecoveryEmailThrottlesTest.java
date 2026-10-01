package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Proves the three recovery-email budgets are configured as decided and stay independent of one another. */
class InMemoryRecoveryEmailThrottlesTest {

    private static final int ATTACH_REQUESTS_PER_DAY = 5;
    private static final int ATTACH_MAILS_PER_DAY = 3;
    private static final int RECOVERY_REQUESTS_PER_DAY = 10;
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final Duration DAY = Duration.ofHours(24);

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
    void tryMail_afterTheDayHasPassed_reopensTheWholeBudget() {
        exhaustMailBudget();

        clock.advance(DAY);

        exhaustMailBudget();
    }

    @Test
    void tryAttach_secondAttachWithinTheCooldown_isDenied() {
        assertThat(throttles.tryAttach(CALLER)).isTrue();

        assertThat(throttles.tryAttach(CALLER)).isFalse();
    }

    @Test
    void tryAttach_afterTheCooldownHasPassed_isAllowedAgain() {
        throttles.tryAttach(CALLER);
        clock.advance(COOLDOWN.minusSeconds(1));
        assertThat(throttles.tryAttach(CALLER)).isFalse();

        clock.advance(Duration.ofSeconds(1));

        assertThat(throttles.tryAttach(CALLER)).isTrue();
    }

    @Test
    void tryAttach_allowsFiveAttachesPerAccountInADayAndDeniesTheSixth() {
        exhaustAttachBudget();
    }

    @Test
    void tryAttach_afterTheDayHasPassed_reopensTheWholeBudget() {
        exhaustAttachBudget();

        clock.advance(DAY);

        exhaustAttachBudget();
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
        exhaustRecoveryBudget();
    }

    @Test
    void tryRequest_afterTheDayHasPassed_reopensTheWholeBudget() {
        exhaustRecoveryBudget();

        clock.advance(DAY);

        exhaustRecoveryBudget();
    }

    @Test
    void reset_reopensOnlyTheRecoveryBudgetAndLeavesTheMailBudgetClosed() {
        exhaustMailBudget();
        exhaustRecoveryBudget();

        throttles.reset(DIGEST);

        assertThat(throttles.tryRequest(DIGEST)).isTrue();
        assertThat(throttles.tryMail(DIGEST)).isFalse();
    }

    @Test
    void exhaustingTheMailBudget_leavesTheRecoveryAndAttachBudgetsOpen() {
        exhaustMailBudget();

        assertThat(throttles.tryRequest(DIGEST)).isTrue();
        assertThat(throttles.tryAttach(CALLER)).isTrue();
    }

    @Test
    void exhaustingTheRecoveryBudget_leavesTheMailAndAttachBudgetsOpen() {
        exhaustRecoveryBudget();

        assertThat(throttles.tryMail(DIGEST)).isTrue();
        assertThat(throttles.tryAttach(CALLER)).isTrue();
    }

    @Test
    void exhaustingTheAttachBudget_leavesTheMailAndRecoveryBudgetsOpen() {
        exhaustAttachBudget();

        assertThat(throttles.tryMail(DIGEST)).isTrue();
        assertThat(throttles.tryRequest(DIGEST)).isTrue();
    }

    private void exhaustAttachBudget() {
        exhaust(() -> throttles.tryAttach(CALLER), ATTACH_REQUESTS_PER_DAY);
    }

    private void exhaustMailBudget() {
        exhaust(() -> throttles.tryMail(DIGEST), ATTACH_MAILS_PER_DAY);
    }

    private void exhaustRecoveryBudget() {
        exhaust(() -> throttles.tryRequest(DIGEST), RECOVERY_REQUESTS_PER_DAY);
    }

    /**
     * Takes exactly {@code budget} grants, waiting out the cooldown between them, and proves the
     * next one is denied by the cap rather than by the cooldown. Bounded, so a broken throttle fails
     * the test instead of hanging it.
     */
    private void exhaust(BooleanSupplier acquire, int budget) {
        for (int grant = 1; grant <= budget; grant++) {
            assertThat(acquire.getAsBoolean()).as("grant %d of %d", grant, budget).isTrue();
            clock.advance(COOLDOWN);
        }

        assertThat(acquire.getAsBoolean()).as("grant beyond the budget of %d", budget).isFalse();
    }
}
