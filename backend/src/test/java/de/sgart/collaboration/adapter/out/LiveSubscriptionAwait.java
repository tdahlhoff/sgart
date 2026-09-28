package de.sgart.collaboration.adapter.out;

import java.util.function.BooleanSupplier;

/**
 * Shared bounded-poll helper for tests observing an eventually-consistent live KurrentDB
 * subscription (DRY) — extracted so {@link HouseholdLiveSyncFanoutIntegrationTest} and {@link
 * MultiPrefixKurrentDbSubscriptionRegressionTest} poll with the identical bounded timeout instead
 * of each keeping its own copy.
 */
final class LiveSubscriptionAwait {

    private static final int MAX_ATTEMPTS = 80;
    private static final long POLL_INTERVAL_MILLIS = 250;

    private LiveSubscriptionAwait() {}

    /** Polls {@code condition} until it is true, or fails after the bounded timeout elapses. */
    static void awaitTrue(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError(failureMessage);
    }
}
