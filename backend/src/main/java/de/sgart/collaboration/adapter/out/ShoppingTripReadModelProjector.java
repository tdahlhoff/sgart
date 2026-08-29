package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.event.StoreAddedToTrip;
import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.StreamId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.RecordedEvent;
import io.kurrent.dbclient.ResolvedEvent;
import io.kurrent.dbclient.SubscribeToAllOptions;
import io.kurrent.dbclient.Subscription;
import io.kurrent.dbclient.SubscriptionFilter;
import io.kurrent.dbclient.SubscriptionListener;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The third projector (Story 3.2, after {@link HouseholdReadModelProjector} and {@link
 * ShoppingListReadModelProjector}) — the trip read side 3.1 deliberately deferred (Cl. 2/4).
 * Subscribes to every {@code trip-} stream and folds {@code TripStarted}/{@code StoreAddedToTrip}
 * into the PostgreSQL trip-store read model ({@link JdbcTripStoreReadModel}) — command handlers
 * never write the read model directly (AD-4). A {@code fromStart} catch-up subscription
 * <strong>retroactively projects the 3.1-created {@code TripStarted} streams</strong> — nothing
 * projected {@code trip-} events until this story, so the first run recovers every already-started
 * trip's stores (Cl. 2/4).
 *
 * <p>Runs as a {@link SmartLifecycle} whose auto-start is gated by a flag (default off), mirroring
 * {@link ShoppingListReadModelProjector} exactly — its own {@code trip-} subscription, distinct
 * from the household/list projectors' prefixes, so no shared subscription. Eventually consistent
 * (AR3/NFR9). The subscription resubscribes on a dropped connection and logs-and-skips a failure
 * projecting one event rather than tearing the subscription down; re-projection is idempotent
 * (upserts), so replay-from-start after a reconnect is safe.
 */
public final class ShoppingTripReadModelProjector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ShoppingTripReadModelProjector.class);
    private static final Duration RESUBSCRIBE_DELAY = Duration.ofSeconds(5);

    private final KurrentDBClient client;
    private final JdbcTripStoreReadModel tripStoreReadModel;
    private final DomainEventJsonCodec codec = new DomainEventJsonCodec();
    private final boolean autoStart;

    private volatile boolean running;
    private ScheduledExecutorService resubscribeScheduler;

    public ShoppingTripReadModelProjector(KurrentDBClient client, JdbcTripStoreReadModel tripStoreReadModel) {
        this(client, tripStoreReadModel, false);
    }

    public ShoppingTripReadModelProjector(
            KurrentDBClient client, JdbcTripStoreReadModel tripStoreReadModel, boolean autoStart) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.tripStoreReadModel = Objects.requireNonNull(tripStoreReadModel, "tripStoreReadModel must not be null");
        this.autoStart = autoStart;
    }

    /** Folds one event into the read model. Idempotent — safe to call again for the same event. */
    public void project(DomainEvent event) {
        switch (event) {
            case TripStarted started ->
                started.storeIds().forEach(storeId -> tripStoreReadModel.addStore(started.tripId(), storeId));
            case StoreAddedToTrip added -> tripStoreReadModel.addStore(added.tripId(), added.storeId());
            default -> {
                // The subscription filter (see subscribe()) only ever delivers trip-stream events;
                // no other trip event exists in 3.2.
            }
        }
    }

    /**
     * Opens the catch-up-then-live subscription across every trip stream, projecting each event as
     * it arrives and resubscribing if the connection drops. Never opens a connection at bean
     * construction — only here, once (auto-)started against a reachable KurrentDB.
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        resubscribeScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shopping-trip-projector-resubscribe");
            thread.setDaemon(true);
            return thread;
        });
        subscribe();
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (resubscribeScheduler != null) {
            resubscribeScheduler.shutdownNow();
            resubscribeScheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }

    private void subscribe() {
        SubscriptionFilter filter = SubscriptionFilter.newBuilder()
                .addStreamNamePrefix(StreamId.StreamType.TRIP.prefix() + "-")
                .build();
        client.subscribeToAll(
                new SubscriptionListener() {
                    @Override
                    public void onEvent(Subscription subscription, ResolvedEvent resolvedEvent) {
                        RecordedEvent recordedEvent = resolvedEvent.getOriginalEvent();
                        try {
                            project(codec.fromJsonBytes(recordedEvent.getEventType(), recordedEvent.getEventData()));
                        } catch (RuntimeException failure) {
                            // Never let one bad event tear down the whole subscription — log and skip;
                            // re-projection is idempotent, so a later replay recovers it.
                            log.error("Failed to project shopping trip event {}", recordedEvent.getEventType(), failure);
                        }
                    }

                    @Override
                    public void onCancelled(Subscription subscription, Throwable throwable) {
                        if (throwable != null) {
                            log.warn("Shopping trip read-model subscription dropped; resubscribing", throwable);
                        }
                        scheduleResubscribe();
                    }
                },
                SubscribeToAllOptions.get().fromStart().filter(filter));
    }

    private synchronized void scheduleResubscribe() {
        if (running && resubscribeScheduler != null) {
            resubscribeScheduler.schedule(this::subscribe, RESUBSCRIBE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
