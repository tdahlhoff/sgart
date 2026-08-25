package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
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
 * The first projector (Story 1.6): subscribes to every household stream and folds {@code
 * HouseholdCreated}/{@code MemberJoined} into the PostgreSQL read model ({@link
 * JdbcHouseholdReadModel}) — command handlers never write the read model directly (AD-4, "the
 * read-models-are-projection-only rule"). Eventually consistent (AR3/NFR9): the create-household
 * response carries the new {@code householdId} so first-run routing does not wait on this catching
 * up (Clarification 4).
 *
 * <p>Runs as a {@link SmartLifecycle} whose auto-start is gated by a flag (default off), mirroring
 * how the datasource/Flyway wiring is gated so {@code contextLoads()} survives the infrastructure
 * being down: constructing the bean performs no I/O, and the live subscription only opens when a
 * real deployment enables it against a reachable KurrentDB. The subscription is resilient — a
 * dropped connection resubscribes rather than silently dying, and a failure projecting one event
 * is logged without tearing the subscription down. Re-projection is idempotent (upserts), so
 * re-reading the stream from the start after a reconnect or restart is safe (a durable position
 * checkpoint remains a future optimization, not a correctness need at this scale).
 */
public final class HouseholdReadModelProjector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HouseholdReadModelProjector.class);
    private static final Duration RESUBSCRIBE_DELAY = Duration.ofSeconds(5);

    private final KurrentDBClient client;
    private final JdbcHouseholdReadModel readModel;
    private final JdbcStoreReadModel storeReadModel;
    private final DomainEventJsonCodec codec = new DomainEventJsonCodec();
    private final boolean autoStart;

    private volatile boolean running;
    private ScheduledExecutorService resubscribeScheduler;

    public HouseholdReadModelProjector(
            KurrentDBClient client, JdbcHouseholdReadModel readModel, JdbcStoreReadModel storeReadModel) {
        this(client, readModel, storeReadModel, false);
    }

    public HouseholdReadModelProjector(
            KurrentDBClient client,
            JdbcHouseholdReadModel readModel,
            JdbcStoreReadModel storeReadModel,
            boolean autoStart) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.readModel = Objects.requireNonNull(readModel, "readModel must not be null");
        this.storeReadModel = Objects.requireNonNull(storeReadModel, "storeReadModel must not be null");
        this.autoStart = autoStart;
    }

    /**
     * Folds one event into the read model. Idempotent — safe to call again for the same event.
     * Store events ({@code StoreAdded}/{@code StoreArchived}) share the household stream (Story 1.8,
     * AD-10), so this one subscription (see {@link #start()}) already carries them — a second
     * subscription over the same prefix would be wrong.
     */
    public void project(DomainEvent event) {
        switch (event) {
            case HouseholdCreated created -> readModel.upsertHousehold(created.householdId(), created.name());
            case HouseholdRenamed renamed -> readModel.upsertHousehold(renamed.householdId(), renamed.newName());
            case MemberJoined joined -> readModel.addMember(joined.householdId(), joined.memberId());
            case StoreAdded added ->
                storeReadModel.upsertStore(added.householdId(), added.storeId(), added.name(), added.chainId());
            case StoreArchived archived -> storeReadModel.markArchived(archived.householdId(), archived.storeId());
            default -> {
                // The subscription filter (see start()) only ever delivers household-stream events.
            }
        }
    }

    /**
     * Opens the catch-up-then-live subscription across every household stream, projecting each
     * event as it arrives and resubscribing if the connection drops. Never opens a connection at
     * bean construction — only here, once (auto-)started against a reachable KurrentDB. Safe to
     * call when KurrentDB is unreachable: the subscription attempt is asynchronous and a failure
     * schedules a retry rather than throwing.
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        resubscribeScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "household-projector-resubscribe");
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
                .addStreamNamePrefix(StreamId.StreamType.HOUSEHOLD.prefix() + "-")
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
                            log.error("Failed to project household event {}", recordedEvent.getEventType(), failure);
                        }
                    }

                    @Override
                    public void onCancelled(Subscription subscription, Throwable throwable) {
                        if (throwable != null) {
                            log.warn("Household read-model subscription dropped; resubscribing", throwable);
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
