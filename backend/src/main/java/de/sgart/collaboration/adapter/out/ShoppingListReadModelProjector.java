package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
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
 * The second projector (Story 2.1): subscribes to every {@code list-} stream and folds {@code
 * ShoppingListCreated}/{@code ShoppingListRenamed} into the PostgreSQL read model ({@link
 * JdbcShoppingListReadModel}) — command handlers never write the read model directly (AD-4). A
 * separate projector from {@link HouseholdReadModelProjector} — that one's subscription filter is
 * the {@code household-} prefix, so lists need their own subscription over the {@code list-}
 * prefix rather than overloading it. Eventually consistent (AR3/NFR9): the create-list response
 * carries no body (the client minted the {@code listId}), so first paint never waits on this
 * catching up.
 *
 * <p>Runs as a {@link SmartLifecycle} whose auto-start is gated by a flag (default off), mirroring
 * {@link HouseholdReadModelProjector} exactly, so {@code contextLoads()} survives KurrentDB/Postgres
 * being down: constructing the bean performs no I/O, and the live subscription only opens when a
 * real deployment enables it against a reachable KurrentDB. The subscription resubscribes on a
 * dropped connection and logs-and-skips a failure projecting one event rather than tearing the
 * subscription down; re-projection is idempotent (upserts), so replay-from-start after a reconnect
 * is safe.
 */
public final class ShoppingListReadModelProjector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ShoppingListReadModelProjector.class);
    private static final Duration RESUBSCRIBE_DELAY = Duration.ofSeconds(5);

    private final KurrentDBClient client;
    private final JdbcShoppingListReadModel readModel;
    private final JdbcItemReadModel itemReadModel;
    private final DomainEventJsonCodec codec = new DomainEventJsonCodec();
    private final boolean autoStart;

    private volatile boolean running;
    private ScheduledExecutorService resubscribeScheduler;

    public ShoppingListReadModelProjector(
            KurrentDBClient client, JdbcShoppingListReadModel readModel, JdbcItemReadModel itemReadModel) {
        this(client, readModel, itemReadModel, false);
    }

    public ShoppingListReadModelProjector(
            KurrentDBClient client,
            JdbcShoppingListReadModel readModel,
            JdbcItemReadModel itemReadModel,
            boolean autoStart) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.readModel = Objects.requireNonNull(readModel, "readModel must not be null");
        this.itemReadModel = Objects.requireNonNull(itemReadModel, "itemReadModel must not be null");
        this.autoStart = autoStart;
    }

    /** Folds one event into the read model. Idempotent — safe to call again for the same event. */
    public void project(DomainEvent event) {
        switch (event) {
            case ShoppingListCreated created ->
                readModel.insertList(created.householdId(), created.listId(), created.name());
            case ShoppingListRenamed renamed -> readModel.renameList(renamed.listId(), renamed.newName());
            case ItemAdded added -> itemReadModel.insertItem(
                    added.householdId(), added.listId(), added.itemId(), added.name(), added.note(), added.quantity());
            case ItemUpdated updated ->
                itemReadModel.updateItem(updated.itemId(), updated.name(), updated.note(), updated.quantity());
            case ItemRemoved removed -> itemReadModel.removeItem(removed.itemId());
            default -> {
                // The subscription filter (see start()) only ever delivers list-stream events.
            }
        }
    }

    /**
     * Opens the catch-up-then-live subscription across every list stream, projecting each event as
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
            Thread thread = new Thread(runnable, "shopping-list-projector-resubscribe");
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
                .addStreamNamePrefix(StreamId.StreamType.LIST.prefix() + "-")
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
                            log.error("Failed to project shopping list event {}", recordedEvent.getEventType(), failure);
                        }
                    }

                    @Override
                    public void onCancelled(Subscription subscription, Throwable throwable) {
                        if (throwable != null) {
                            log.warn("Shopping list read-model subscription dropped; resubscribing", throwable);
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
